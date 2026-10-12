package dev.nuaforma.server

import dev.nuaforma.core.ProviderRegistry
import dev.nuaforma.server.adapters.ktor.KtorServer
import dev.nuaforma.server.internal.ServerProperties
import dev.nuaforma.server.internal.ExtensionClasspath
import java.nio.file.Path

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty()) { "Configure the server using JVM system properties" }
        val configuration = ServerProperties.read(System.getProperties())
        val parent = Thread.currentThread().contextClassLoader ?: Main::class.java.classLoader
        val directory = System.getProperty("nuaforma.extensions.directory")?.let { Path.of(it) }
        ExtensionClasspath.open(directory, parent).use { loader ->
            val thread = Thread.currentThread()
            val original = thread.contextClassLoader
            thread.contextClassLoader = loader
            try { KtorServer(ProviderRegistry.discover(loader), configuration).startAndWait() }
            finally { thread.contextClassLoader = original }
        }
    }
}
