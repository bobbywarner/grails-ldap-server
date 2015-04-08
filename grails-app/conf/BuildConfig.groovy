grails.project.dependency.resolver = "maven"
grails.project.dependency.resolution = {
	inherits( "global" )
	repositories {
		mavenCentral()
		grailsCentral()
	}

	dependencies {
		compile 'org.apache.directory.server:apacheds-core:1.5.4'
		compile 'org.apache.directory.server:apacheds-protocol-ldap:1.5.4'
		// the following transitive dep is included to workaround a failure during a clean compile
		compile 'org.apache.directory.shared:shared-ldap:0.9.12'
	}

	plugins {
		build (":release:3.1.1", ":rest-client-builder:2.1.1") {
			export = false
		}
		test (":hibernate:3.6.10.18", ":tomcat:7.0.55.2") {
			export = false
		}
	}
}
