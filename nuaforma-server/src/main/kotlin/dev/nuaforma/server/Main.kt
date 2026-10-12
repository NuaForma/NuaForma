package dev.nuaforma.server

import dev.nuaforma.core.ProviderRegistry
import dev.nuaforma.server.adapters.ktor.KtorServer
import dev.nuaforma.server.internal.ServerProperties

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty()) { "Configure the server using JVM system properties" }
        KtorServer(ProviderRegistry.discover(), ServerProperties.read(System.getProperties())).startAndWait()
    }
}
