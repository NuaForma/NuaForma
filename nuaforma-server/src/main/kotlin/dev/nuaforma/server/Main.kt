package dev.nuaforma.server

import dev.nuaforma.core.ProviderRegistry
import dev.nuaforma.server.adapters.ktor.KtorServer
import dev.nuaforma.server.adapters.logging.OperationalLogging
import dev.nuaforma.contracts.LoggingMode
import dev.nuaforma.server.internal.ServerProperties
import dev.nuaforma.server.internal.ExtensionClasspath
import dev.nuaforma.server.internal.ShippedSbom
import java.nio.file.Path

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        run(args, null)
    }

    /** Starts the same server with an optional explicitly composed development UI. */
    @JvmStatic
    fun run(args: Array<String>, webApplication: WebApplication?) {
        try { start(args, webApplication) }
        catch (error: Exception) {
            OperationalLogging.configured(LoggingMode.PRODUCTION).failure("SERVER_STARTUP_FAILED", error)
            throw IllegalStateException("Server startup failed; inspect operational logs")
        }
    }

    private fun start(args: Array<String>, webApplication: WebApplication?) {
        require(args.isEmpty()) { "Configure the server using JVM system properties" }
        val mode = try { ServerProperties.mode(System.getenv("NUAFORMA_MODE")) }
            catch (error: IllegalArgumentException) {
                OperationalLogging.configured(LoggingMode.PRODUCTION).operation("SERVER_CONFIGURATION_REJECTED",
                    mapOf("setting" to "NUAFORMA_MODE", "reason" to "Expected production, development or unset; empty values are invalid"))
                throw error
            }
        val configuration = ServerConfiguration(ServerProperties.read(System.getProperties()), mode)
        val playground = ServerProperties.playground(System.getenv("NUAFORMA_PLAYGROUND"), mode, webApplication != null)
        if (mode == LoggingMode.DEVELOPMENT) OperationalLogging.developmentWarning()
        if (playground) OperationalLogging.playgroundWarning()
        val startupLogging = OperationalLogging.configured(mode)
        val sbomPath = System.getenv("NUAFORMA_SBOM_PATH")
        val sbom = if (sbomPath == null && webApplication?.inventory != null) ShippedSbom.read(webApplication.inventory!!.inputStream())
            else ShippedSbom.configured(sbomPath)
        val parent = Thread.currentThread().contextClassLoader ?: Main::class.java.classLoader
        val directory = System.getProperty("nuaforma.extensions.directory")?.let { Path.of(it) }
        ExtensionClasspath.open(directory, parent).use { loader ->
            val thread = Thread.currentThread()
            val original = thread.contextClassLoader
            thread.contextClassLoader = loader
            try { KtorServer(ProviderRegistry.discover(loader, emptyList(), startupLogging), configuration,
                webApplication = if (playground) webApplication else null, sbom = sbom).startAndWait() }
            finally { thread.contextClassLoader = original }
        }
    }
}
