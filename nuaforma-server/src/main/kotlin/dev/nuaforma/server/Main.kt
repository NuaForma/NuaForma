package dev.nuaforma.server

import dev.nuaforma.core.ProviderRegistry
import dev.nuaforma.server.adapters.ktor.KtorServer

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty()) { "Configure the server using JVM system properties" }
        KtorServer(ProviderRegistry.discover(), ServerConfiguration(
            host = System.getProperty("nuaforma.server.host", "127.0.0.1"),
            port = System.getProperty("nuaforma.server.port", "8080").toInt(),
        )).startAndWait()
    }
}
