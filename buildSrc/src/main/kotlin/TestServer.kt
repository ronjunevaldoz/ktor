/*
 * Copyright 2014-2022 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */
import org.gradle.api.file.*
import org.gradle.api.provider.*
import org.gradle.api.services.*
import java.io.*
import java.net.*

abstract class TestServer : BuildService<TestServer.Params>, AutoCloseable {
    interface Params : BuildServiceParameters {
        val main: Property<String>
        val classpath: Property<FileCollection>
    }

    val server: Closeable

    init {
        println("[TestServer] start")
        val classpath = parameters.classpath.get()
        val mainPath = parameters.main.get()

        val urlClassLoaderSource = classpath.map { file -> file.toURI().toURL() }.toTypedArray()
        val loader = URLClassLoader(urlClassLoaderSource, ClassLoader.getSystemClassLoader())

        val mainClass = loader.loadClass(mainPath)
        val main = mainClass.getMethod("startServer")
        server = try {
            main.invoke(null) as Closeable
        } catch (cause: Throwable) {
            println("[TestServer] failed: ${cause.message}")
            cause.printStackTrace()
            throw cause
        }
    }

    override fun close() {
        server.close()
    }
}
