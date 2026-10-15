package dev.nuaforma.server

import dev.nuaforma.contracts.LoggingMode
import dev.nuaforma.core.ProviderRegistry
import dev.nuaforma.server.adapters.ktor.KtorServer
import dev.nuaforma.server.internal.ServerProperties
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PlaygroundHostingTest {
    private fun application() = WebApplication(listOf(WebApplication.Asset("/playground/", "text/html", "<!doctype html>Development".toByteArray())))

    @Test fun `activation requires explicit development mode and installed module`() {
        for (value in listOf(null, "false")) assertFalse(ServerProperties.playground(value, LoggingMode.PRODUCTION, false))
        assertTrue(ServerProperties.playground("true", LoggingMode.DEVELOPMENT, true))
        assertThrows(IllegalArgumentException::class.java) { ServerProperties.playground("true", LoggingMode.PRODUCTION, true) }
        assertThrows(IllegalArgumentException::class.java) { ServerProperties.playground("true", LoggingMode.DEVELOPMENT, false) }
        for (value in listOf("", "TRUE", "yes")) assertThrows(IllegalArgumentException::class.java) { ServerProperties.playground(value, LoggingMode.DEVELOPMENT, true) }
        assertThrows(IllegalArgumentException::class.java) { KtorServer(ProviderRegistry.registered(emptyList()), ServerConfiguration(), webApplication = application()) }
    }

    @Test fun `production exposes no Playground route`() = testApplication {
        application { KtorServer(ProviderRegistry.registered(emptyList()), ServerConfiguration()).installRoutes(this) }
        assertEquals(HttpStatusCode.NotFound, client.get("/playground/").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/playground/app.js").status)
    }

    @Test fun `explicit assets are served with restrictive browser headers and unchanged API`() = testApplication {
        application { KtorServer(ProviderRegistry.registered(emptyList()), ServerConfiguration(ServerConfiguration(), LoggingMode.DEVELOPMENT), webApplication = application()).installRoutes(this) }
        val response = client.get("/playground/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("<!doctype html>Development", response.bodyAsText())
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertTrue(response.headers["Content-Security-Policy"]!!.contains("frame-ancestors 'none'"))
        assertEquals(HttpStatusCode.NotFound, client.get("/playground/private.txt").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/version").status)
        assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
        val contract = com.fasterxml.jackson.databind.ObjectMapper().readTree(client.get("/openapi.json").bodyAsText())
        assertTrue(contract.path("paths").fieldNames().asSequence().none { it == "/playground" || it.startsWith("/playground/") })
    }

    @Test fun `asset composition rejects traversal invalid types duplicates and mutable caller bytes`() {
        val source = "original".toByteArray()
        val asset = WebApplication.Asset("/playground/", "text/html", source)
        source[0] = 0; asset.bytes[0] = 0
        assertEquals("original", String(asset.bytes))
        assertThrows(IllegalArgumentException::class.java) { WebApplication.Asset("/playground/../private.js", "text/javascript", source) }
        assertThrows(IllegalArgumentException::class.java) { WebApplication.Asset("/api/v1/documents", "text/html", source) }
        assertThrows(IllegalArgumentException::class.java) { WebApplication.Asset("/playground/", "application/unknown", source) }
        assertThrows(IllegalArgumentException::class.java) { WebApplication(listOf(asset, asset)) }
    }
}
