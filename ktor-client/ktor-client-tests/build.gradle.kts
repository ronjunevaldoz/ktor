/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

description = "Common tests for client"

val junit_version: String by project.extra
val kotlin_version: String by project.extra
val logback_version: String by project.extra
val serialization_version: String by project.extra
val coroutines_version: String by project
val native_targets_enabled: Boolean by rootProject.extra

plugins {
    id("kotlinx-serialization")
}

useJdkVersionForJvmTests(11)

kotlin.sourceSets {
    commonMain {
        dependencies {
            api(project(":ktor-client:ktor-client-mock"))
            api(project(":ktor-test-dispatcher"))
        }
    }
    commonTest {
        dependencies {
            api(project(":ktor-client:ktor-client-plugins:ktor-client-json"))
            api(project(":ktor-client:ktor-client-plugins:ktor-client-json:ktor-client-serialization"))
            api(project(":ktor-client:ktor-client-plugins:ktor-client-logging"))
            api(project(":ktor-client:ktor-client-plugins:ktor-client-auth"))
            api(project(":ktor-client:ktor-client-plugins:ktor-client-encoding"))
            api(project(":ktor-client:ktor-client-plugins:ktor-client-content-negotiation"))
            api(project(":ktor-client:ktor-client-plugins:ktor-client-json"))
            api(project(":ktor-client:ktor-client-plugins:ktor-client-json:ktor-client-serialization"))
            api(project(":ktor-shared:ktor-serialization:ktor-serialization-kotlinx"))
            api(project(":ktor-shared:ktor-serialization:ktor-serialization-kotlinx:ktor-serialization-kotlinx-json"))
        }
    }
    jvmMain {
        dependencies {
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:$serialization_version")
            api(project(":ktor-network:ktor-network-tls:ktor-network-tls-certificates"))
            api(project(":ktor-server"))
            api(project(":ktor-server:ktor-server-cio"))
            api(project(":ktor-server:ktor-server-netty"))
            api(project(":ktor-server:ktor-server-jetty"))
            api(project(":ktor-server:ktor-server-plugins:ktor-server-auth"))
            api(project(":ktor-server:ktor-server-plugins:ktor-server-websockets"))
            api(project(":ktor-shared:ktor-serialization:ktor-serialization-kotlinx"))
            api("ch.qos.logback:logback-classic:$logback_version")
            api("junit:junit:$junit_version")
            api("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-debug:$coroutines_version")

            // https://github.com/Kotlin/kotlinx.coroutines/issues/3001
            val jna_version = "5.9.0"
            api("net.java.dev.jna:jna:$jna_version")
            api("net.java.dev.jna:jna-platform:$jna_version")
        }
    }

    jvmTest {
        dependencies {
            api(project(":ktor-client:ktor-client-apache"))
            runtimeOnly(project(":ktor-client:ktor-client-cio"))
            runtimeOnly(project(":ktor-client:ktor-client-android"))
            runtimeOnly(project(":ktor-client:ktor-client-okhttp"))
            if (currentJdk >= 11) {
                runtimeOnly(project(":ktor-client:ktor-client-java"))
            }
        }
    }

    jsTest {
        dependencies {
            api(project(":ktor-client:ktor-client-js"))
        }
    }

    if (!native_targets_enabled) return@sourceSets
    listOf("linuxX64Test", "mingwX64Test", "macosX64Test").map { getByName(it) }.forEach {
        it.dependencies {
            api(project(":ktor-client:ktor-client-curl"))
        }
    }

    listOf("linuxX64Test", "macosX64Test", "iosX64Test").map { getByName(it) }.forEach {
        it.dependencies {
            api(project(":ktor-client:ktor-client-cio"))
        }
    }

    listOf("iosX64Test", "macosX64Test", "macosArm64Test").map { getByName(it) }.forEach {
        it.dependencies {
            api(project(":ktor-client:ktor-client-darwin"))
        }
    }
}

val testServerService = project.gradle.sharedServices.registerIfAbsent("TestServer", TestServer::class.java) {
    with(parameters) {
        main.set("io.ktor.client.tests.utils.TestServerKt")
        val kotlinCompilation = kotlin.targets.getByName("jvm")
            .compilations["test"] as org.jetbrains.kotlin.gradle.plugin.KotlinCompilationToRunnableFiles<*>

        classpath.set(kotlinCompilation.runtimeDependencyFiles)
    }
}

val testTasks = listOf(
    "jvmTest",

    "jsLegacyNodeTest",
    "jsIrNodeTest",
    "jsLegacyBrowserTest",
    "jsIrBrowserTest",

    "posixTest",
    "darwinTest",
    "macosX64Test",
    "macosArm64Test",
    "linuxX64Test",
    "iosX64Test",
    "mingwX64Test"
).mapNotNull { tasks.findByName(it) }

configure(testTasks) {
    usesService(testServerService)
}
