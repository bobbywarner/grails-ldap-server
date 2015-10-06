package grails.ldap.server

import grails.plugins.Plugin

class LdapServerGrailsPlugin extends Plugin {

	public static final String beanNameSuffix = 'LdapServer'

	def grailsVersion = '3.0.0 > *'
	def watchedResources = ['file:./grails-app/ldap-servers/*/data/*.ldif',
	                        'file:./grails-app/ldap-servers/*/schema/*.ldif']
	def author = 'Luke Daley'
	def authorEmail = 'ld@ldaley.com'
	def title = 'Embedded LDAP Server Plugin'
	def description = 'Allows the embedding of an LDAP directory (via ApacheDS) for testing purposes'
	def documentation = 'http://grails.org/plugin/grails-ldap-server'
	def license = 'APACHE'
	def scm = [url: 'https://github.com/bobbywarner/grails-ldap-server']
	def issueManagement = [url: 'https://github.com/bobbywarner/grails-ldap-server/issues']

	private List<String> serverBeanNames = []

	Closure doWithSpring() {{ ->
		createServers delegate
	}}

	void onChange(Map<String, Object> event) {
		handleChange event
	}

	void onConfigChange(Map<String, Object> event) {
		handleChange event
	}

	private void handleChange(Map<String, Object> event) {
		serverBeanNames.each {
			applicationContext.getBean(it).stop()
			applicationContext.removeBeanDefinition it
		}
		serverBeanNames.clear()

		beans {
			createServers delegate
		}
	}

	private void createServers(beanBuilder) {
		config.ldapServers.each { name, props ->
			String beanName = name + beanNameSuffix
			beanBuilder."$beanName"(TransientGrailsLdapServer) {
				grailsApplication = grailsApplication

				for (configOption in TransientGrailsLdapServer.configOptions) {
					if (props.containsKey(configOption)) {
						delegate."$configOption" = props[configOption]
					}
				}
			}
			serverBeanNames << beanName
		}
	}
}
