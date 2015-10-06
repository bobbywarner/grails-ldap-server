package grails.ldap.server

import javax.servlet.ServletContext

import org.apache.directory.server.core.DefaultDirectoryService
import org.apache.directory.server.core.partition.Partition
import org.apache.directory.server.core.partition.impl.btree.jdbm.JdbmIndex
import org.apache.directory.server.core.partition.impl.btree.jdbm.JdbmPartition
import org.apache.directory.server.ldap.LdapService
import org.apache.directory.server.protocol.shared.SocketAcceptor
import org.apache.directory.shared.ldap.exception.LdapNameNotFoundException
import org.apache.directory.shared.ldap.ldif.LdifEntry
import org.apache.directory.shared.ldap.ldif.LdifReader
import org.apache.directory.shared.ldap.ldif.LdifUtils
import org.apache.directory.shared.ldap.name.LdapDN
import org.springframework.beans.factory.BeanNameAware
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.InitializingBean
import org.springframework.web.context.ServletContextAware
import org.springframework.web.util.WebUtils

import grails.core.GrailsApplication
import groovy.text.SimpleTemplateEngine
import groovy.util.logging.Slf4j

@Slf4j
class TransientGrailsLdapServer implements InitializingBean, DisposableBean, BeanNameAware, ServletContextAware {

	public static final List<String> configOptions = ['port', 'base', 'indexed']
	public static final FilenameFilter ldifFileNameFilter = [accept: { File dir, String name -> name.endsWith('.ldif') }] as FilenameFilter

	String beanName
	ServletContext servletContext
	GrailsApplication grailsApplication

	Integer port = 10389
	String base = 'dc=grails,dc=org'
	String[] indexed = ['objectClass', 'ou', 'uid']

	DefaultDirectoryService directoryService
	LdapService ldapService

	LdapDN baseDn

	File configDir
	File dataDir
	File fixturesDir
	File schemaDir

	boolean running = false
	boolean initialised = false

	void afterPropertiesSet() {
		if (initialised) {
			return
		}

		if (log.infoEnabled) {
			log.info '{} config: {}', beanName, configOptions.collect { "$it = ${properties[it]}" }.join(', ')
		}

		String baseConfigDirPath = grailsApplication.warDeployed ?
				  grailsApplication.mainContext.getResource('WEB-INF/grails-app/ldap-servers').file.path :
				  'grails-app/ldap-servers'

		File baseConfigDir = new File(baseConfigDirPath)
		configDir = new File(baseConfigDir, beanName - 'LdapServer')
		dataDir = new File(configDir, 'data')
		fixturesDir = new File(configDir, 'fixtures')
		schemaDir = new File(configDir, 'schema')
		baseDn = new LdapDN(base)

		start()
		initialised = true

		addShutdownHook {
			stop()
		}
	}

	void start() {
		if (running) {
			return
		}

		log.info '{} starting', beanName
		startDirectoryService()

		loadLdif schemaDir
		loadLdif dataDir

		directoryService.changeLog.tag()

		startLdapService()
		running = true
		log.info '{} startup complete', beanName
	}

	void stop() {
		if (!running) {
			return
		}

		log.info '{} stopping', beanName
		stopDirectoryService()
		stopLdapService()
		running = false
		log.info '{} stopped', beanName
	}

	void destroy() {
		stop()
	}

	void restart() {
		stop()
		start()
	}

	void clean() {
		if (!running) {
			return
		}

		log.info '{} cleaning', beanName
		directoryService.revert()
		directoryService.changeLog.tag()
	}

	void loadFixture(String fixtureName) {
		loadFixtures fixtureName
	}

	void loadFixture(Map binding, String fixtureName) {
		loadFixtures([fixtureName] as String[], binding)
	}

	void loadFixture(String fixtureName, Map binding) {
		loadFixtures([fixtureName] as String[], binding)
	}

	void loadFixtures(String[] fixtureNames) {
		loadFixtures fixtureNames, [:]
	}

	void loadFixtures(Map binding, String[] fixtureNames) {
		loadFixtures fixtureNames, binding
	}

	void loadFixtures(String[] fixtureNames, Map binding) {
		binding = binding ?: [:]
		fixtureNames.each { fixtureName ->
			def fixture = new File(fixturesDir, fixtureName + '.ldif')
			if (!fixture.exists()) {
				throw new IllegalArgumentException("Cannot load fixture '$fixtureName' as it does not exist")
			}

			log.debug '{}: loading fixture {}, binding = {}', beanName, fixtureName, binding
			loadLdif new SimpleTemplateEngine().createTemplate(new FileReader(fixture)).make(binding)
		}
	}

	void loadLdif(String ldif) {
		log.debug '{}: loading ldif "{}"', beanName, ldif
		consumeLdifReader new LdifReader(new StringReader(ldif))
	}

	void loadLdif(File file) {
		if (!file.exists()) {
			return
		}

		if (file.directory) {
			log.debug 'Loading ldif in dir: {}', file
			file.listFiles(ldifFileNameFilter).sort().each {
				loadLdif it
			}
		}
		else {
			log.debug 'Loading ldif in file: {}', file
			consumeLdifReader new LdifReader(file)
		}
	}

	void loadLdif(ldif) {
		loadLdif(ldif as String)
	}

	boolean exists(String dn) {
		directoryService.adminSession.exists(new LdapDN(dn))
	}

	Map getAt(String dn) {
		try {
			def entry = directoryService.adminSession.lookup(new LdapDN(dn))
			def entryMap = [:]
			entry.attributeTypes.each { at ->
				def attribute = entry.get(at)
				if (at.singleValue) {
					entryMap[attribute.id] = (attribute.isHR()) ? attribute.string : attribute.bytes
				}
				else {
					entryMap[attribute.id] = attribute.all.collect { it.get() }
				}
			}
			entryMap
		}
		catch (LdapNameNotFoundException ignored) {}
	}

	private void startDirectoryService() {

		directoryService = new DefaultDirectoryService()
		directoryService.changeLog.enabled = true
		def workingDir = getWorkDir()
		if (workingDir.exists()) workingDir.deleteDir()
		directoryService.workingDirectory = workingDir

		def partition = addPartition(baseDn.rdn.normValue, base)
		addIndex(partition, *indexed)

		directoryService.startup()
		createBase()
	}

	private void startLdapService() {
		ldapService = new LdapService(
			socketAcceptor: new SocketAcceptor(null),
			directoryService: directoryService,
			ipPort: port)

		ldapService.start()
	}

	private void stopDirectoryService() {
		directoryService.shutdown()
	}

	private void stopLdapService() {
		ldapService.stop()
	}

	private void createBase() {
		def entry = directoryService.newEntry(baseDn)
		entry.add 'objectClass', 'top', 'domain', 'extensibleObject'
		entry.add baseDn.rdn.normType, baseDn.rdn.normValue
		directoryService.adminSession.add entry
	}

	private Partition addPartition(String partitionId, String partitionDn) {
		def partition = new JdbmPartition(id: partitionId, suffix: partitionDn)
		directoryService.addPartition partition

		partition
	}

	private void addIndex(Partition partition, String[] attrs) {
		partition.indexedAttributes = attrs.collect { new JdbmIndex(it) } as Set
	}

	private void consumeLdifReader(LdifReader ldifReader) {
		while (ldifReader.hasNext()) {
			LdifEntry entry = ldifReader.next()
			if (entry.isChangeModify()) {
				directoryService.adminSession.modify entry.dn, entry.modificationList
			}
			else {
				String ldif = LdifUtils.convertToLdif(entry, Integer.MAX_VALUE)
				directoryService.adminSession.add directoryService.newEntry(ldif, entry.dn.toString())
			}
		}
	}

	private File getWorkDir() {
		new File(WebUtils.getTempDir(servletContext), 'ldap-servers/' + beanName)
	}
}
