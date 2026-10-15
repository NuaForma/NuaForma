package dev.nuaforma.server

import java.net.HttpURLConnection
import java.net.URI

/** Standalone container probe. No provider discovery, logging backend or document processing. */
object HealthCheck {
    @JvmStatic fun main(args: Array<String>) {
        val port = args.singleOrNull()?.toIntOrNull()?.takeIf { it in 1..65535 }
        if (port == null || !ready(port)) kotlin.system.exitProcess(1)
    }

    internal fun ready(port: Int): Boolean {
        val connection = URI("http://127.0.0.1:$port/health/ready").toURL().openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 1500
            connection.readTimeout = 1500
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.responseCode == 200
        } catch (_: Exception) { false }
        finally { connection.disconnect() }
    }
}
