val json_simple_version: String by project.extra
val java_jwt_version: String by project.extra
val jwks_rsa_version: String by project.extra
val mokito_kotlin_version: String by project.extra

kotlin.sourceSets {
    jvmMain {
        dependencies {
            api(project(":ktor-server:ktor-server-plugins:ktor-server-auth"))
            api("com.auth0:java-jwt:$java_jwt_version")
            api("com.auth0:jwks-rsa:$jwks_rsa_version")
        }
    }
    jvmTest {
        dependencies {
            api("com.nhaarman:mockito-kotlin:$mokito_kotlin_version")
        }
    }
}

