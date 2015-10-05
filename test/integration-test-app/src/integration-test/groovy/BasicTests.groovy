import grails.ldap.server.TransientGrailsLdapServer
import grails.test.mixin.integration.Integration
import spock.lang.Shared
import spock.lang.Specification

@Integration
class BasicTests extends Specification {

	@Shared d1LdapServer
	@Shared d2LdapServer
	def grailsApplication

	void 'server beans exist'() {
		expect:
		d1LdapServer
		d2LdapServer
	}

	void 'config server 1'() {
		when:
		def server = d1LdapServer
		def conf = grailsApplication.config.ldapServers[server.beanName - 'LdapServer']
		def defaultServer = new TransientGrailsLdapServer()
		def expected
		def message
		if (conf.containsKey(configOption)) {
			expected = conf[configOption]
			message = "$server.beanName: $configOption does not match config value"
		}
		else {
			expected = defaultServer."$configOption"
			message = "$server.beanName: $configOption does not match default value"
		}

		then:
		def actual = server."$configOption"
		println "expected '$expected' : actual '$actual"
		assert expected == server."$configOption", message

		where:
		configOption << TransientGrailsLdapServer.configOptions
	}

	void 'config server 2'() {
		when:
		def server = d2LdapServer
		def conf = grailsApplication.config.ldapServers[server.beanName - 'LdapServer']
		def defaultServer = new TransientGrailsLdapServer()
		def expected
		def message
		if (conf.containsKey(configOption)) {
			expected = conf[configOption]
			message = "$server.beanName: $configOption does not match config value"
		}
		else {
			expected = defaultServer."$configOption"
			message = "$server.beanName: $configOption does not match default value"
		}

		then:
		def actual = server."$configOption"
		println "expected '$expected' : actual '$actual"
		assert expected == server."$configOption", message

		where:
		configOption << TransientGrailsLdapServer.configOptions
	}

	void 'stop start restart'() {
		expect:
		server.running
		server.directoryService.started

		when:
		server.stop()

		then:
		!server.running
		!server.directoryService.started

		when:
		server.restart()

		then:
		server.running
		server.directoryService.started

		where:
		server << [d1LdapServer, d2LdapServer]
	}

	void 'load data'() {
		expect:
		d1LdapServer.exists 'ou=test,dc=d1'
	}

	void 'load fixtures'() {
		when:
		d2LdapServer.loadFixture 'testou'

		then:
		d2LdapServer.exists 'ou=test,dc=d2'

		when:
		d2LdapServer.clean()
		d2LdapServer.loadFixtures 'testou', 'country'

		then:
		d2LdapServer.exists 'c=au,dc=d2'
	}

	void 'load templated fixtures'() {
		when:
		d2LdapServer.loadFixture 'personTemplate', cn: 'cn1', sn: 'sn1'

		then:
		d2LdapServer.exists 'cn=cn1,dc=d2'

		when:
		d2LdapServer.loadFixtures 'personTemplate', 'ouTemplate', cn: 'cn2', sn: 'sn2', ou: 'ou1'

		then:
		d2LdapServer.exists 'cn=cn2,dc=d2'
		d2LdapServer.exists 'ou=ou1,dc=d2'

		when:
		d2LdapServer.loadFixture 'personTemplate', [cn: 'cn3', sn: 'sn3']

		then:
		d2LdapServer.exists 'cn=cn3,dc=d2'

		// Commented out because Groovy doesn't support this syntax properly.
//		when:
//		d2LdapServer.loadFixtures 'personTemplate', 'ouTemplate', [cn: 'cn4', sn: 'sn4', ou: 'ou2']
//
//		then:
//		d2LdapServer.exists 'cn=cn4,dc=d2'
//		d2LdapServer.exists 'ou=ou2,dc=d2'
	}

	void 'load ldif'() {
		when:
		d2LdapServer.loadLdif '''
dn: cn=cn3,dc=d2
cn: cn3
sn: sn
objectClass: person
objectClass: top
objectClass: organizationalPerson
'''
		then:
		d2LdapServer.exists 'cn=cn3,dc=d2'
	}

	void testLoadSchemaLdif() {
		when:
		d2LdapServer.loadLdif '''
dn: cn=schema
changetype: modify
add: attributetypes
attributetypes: ( 1.3.6.1.4.1.99999.2.1.0.0 NAME 'myRegionalOffice' EQUALITY caseIgnoreMatch SUBSTR caseIgnoreSubstringsMatch SYNTAX '1.3.6.1.4.1.1466.115.121.1.15' )
-
add: objectclasses
objectclasses: (  1.3.6.1.4.1.99999.2.3.1.0.0  NAME 'myUser' SUP 'person' STRUCTURAL MAY ( myRegionalOffice ) )
-
'''
		d2LdapServer.loadLdif '''
dn: cn=cn3,dc=d2
cn: cn3
sn: sn
objectClass: person
objectClass: top
objectClass: myUser
myRegionalOffice: nowhere
'''

		then:
		d2LdapServer.exists 'cn=cn3,dc=d2'
	}

	void 'clean'() {
		when:
		d2LdapServer.loadFixture 'testou'

		then:
		d2LdapServer.exists 'ou=test,dc=d2'

		when:
		d2LdapServer.clean()

		then:
		!d2LdapServer.exists('ou=test,dc=d2')
	}

	void 'getAt'() {
		when:
		d2LdapServer.loadFixture 'some'
		def entry = d2LdapServer['cn=cn2,dc=d2']

		then:
		entry instanceof Map
		entry.cn instanceof List
		'cn2' == entry.cn.first()
		entry.usercertificate.first() instanceof byte[]

		when:
		d2LdapServer.loadFixture('country')
		entry = d2LdapServer['c=au,dc=d2']

		then:
		'au' == entry.c
	}

	void cleanup() {
		[d1LdapServer, d2LdapServer]*.clean()
	}
}
