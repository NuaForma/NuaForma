package dev.nuaforma.server

import dev.nuaforma.core.ProviderRegistry
import dev.nuaforma.server.adapters.ktor.KtorServer
import dev.nuaforma.server.adapters.logging.OperationalLogging
import dev.nuaforma.contracts.LoggingMode
import dev.nuaforma.server.internal.ServerProperties
import dev.nuaforma.server.internal.ExtensionClasspath
import java.nio.file.Path

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        try { start(args) }
        catch (error: Exception) {
            OperationalLogging.configured(LoggingMode.PRODUCTION).failure("SERVER_STARTUP_FAILED", error)
            throw IllegalStateException("Server startup failed; inspect operational logs")
        }
    }

    private fun start(args: Array<String>) {
        require(args.isEmpty()) { "Configure the server using JVM system properties" }
        val mode = try { ServerProperties.mode(System.getenv("NUAFORMA_MODE")) }
            catch (error: IllegalArgumentException) {
                OperationalLogging.configured(LoggingMode.PRODUCTION).operation("SERVER_CONFIGURATION_REJECTED",
                    mapOf("setting" to "NUAFORMA_MODE", "reason" to "Expected production, development or unset; empty values are invalid"))
                throw error
            }
        val configuration = ServerConfiguration(ServerProperties.read(System.getProperties()), mode)
        if (mode == LoggingMode.DEVELOPMENT) OperationalLogging.developmentWarning()
        val startupLogging = OperationalLogging.configured(mode)
        val parent = Thread.currentThread().contextClassLoader ?: Main::class.java.classLoader
        val directory = System.getProperty("nuaforma.extensions.directory")?.let { Path.of(it) }
        ExtensionClasspath.open(directory, parent).use { loader ->
            val thread = Thread.currentThread()
            val original = thread.contextClassLoader
            thread.contextClassLoader = loader
            try { KtorServer(ProviderRegistry.discover(loader, emptyList(), startupLogging), configuration).startAndWait() }
            finally { thread.contextClassLoader = original }
        }
    }
}
