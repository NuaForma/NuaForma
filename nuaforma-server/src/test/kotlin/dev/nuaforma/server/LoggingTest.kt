package dev.nuaforma.server

import dev.nuaforma.contracts.*
import dev.nuaforma.core.ProviderRegistry
import dev.nuaforma.server.adapters.ktor.KtorServer
import dev.nuaforma.server.adapters.ktor.RequestLogging
import dev.nuaforma.server.adapters.logging.OperationalLogging
import dev.nuaforma.server.internal.ServerProperties
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Collections
import java.util.UUID

class LoggingTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `mode defaults and strict values never echo invalid configuration`() {
        assertEquals(LoggingMode.PRODUCTION, ServerProperties.mode(null))
        assertEquals(LoggingMode.PRODUCTION, ServerProperties.mode("production"))
        assertEquals(LoggingMode.DEVELOPMENT, ServerProperties.mode("development"))
        for (invalid in listOf("", "develop", " DEVELOPMENT ", "PRIVATE_TOKEN")) {
            val error = assertThrows(IllegalArgumentException::class.java) { ServerProperties.mode(invalid) }
            assertFalse(error.message.orEmpty().contains("PRIVATE_TOKEN"))
        }
    }

    @Test
    fun `every valid invalid unknown and concurrent request gets an independent identity`() = testApplication {
        val events = Collections.synchronizedList(mutableListOf<GenerationLogEvent>())
        application { KtorServer(ProviderRegistry.registered(emptyList()), ServerConfiguration(temporaryDirectory = temporary), events::add).installRoutes(this) }
        val responses = coroutineScope { (0..15).map { index -> async {
            if (index % 2 == 0) client.get("/api/v1/version") { header("X-Request-ID", "attacker-owned") }
            else client.post("/api/v1/assess") { contentType(ContentType.Application.Json); setBody("{\"PRIVATE\":}") }
        } }.awaitAll() }
        val ids = responses.map { response ->
            val id = requireNotNull(response.headers["X-Request-ID"])
            UUID.fromString(id)
            if (response.status != HttpStatusCode.OK) {
                assertTrue(response.bodyAsText().contains("\"requestId\":\"$id\""))
                assertFalse(response.bodyAsText().contains("PRIVATE"))
            }
            id
        }
        assertEquals(ids.size, ids.toSet().size)
        val unknown = client.get("/unknown")
        assertEquals(HttpStatusCode.NotFound, unknown.status)
        assertTrue(unknown.bodyAsText().contains(requireNotNull(unknown.headers["X-Request-ID"])))
        val capturedEvents = synchronized(events) { events.toList() }
        assertTrue(capturedEvents.none { it.requestId == "attacker-owned" || it.text.contains("PRIVATE") })
        assertTrue(ids.all { id -> capturedEvents.any { it.requestId == id && it.code == "REQUEST_STARTED" } })
    }

    @Test
    fun `MDC follows coroutine IO nesting failures cancellation and thread reuse`() = runBlocking {
        val original = OperationalLogging.snapshot()
        try {
            withContext(RequestLogging(GenerationLogging(requestId = "outer"))) {
                assertEquals("outer", OperationalLogging.snapshot()?.get("requestId"))
                coroutineScope { (0..15).map { index -> async(Dispatchers.Default) {
                    val id = "request-$index"
                    withContext(RequestLogging(GenerationLogging(requestId = id))) {
                        repeat(5) { yield(); assertEquals(id, OperationalLogging.snapshot()?.get("requestId")) }
                        runInterruptible(Dispatchers.IO) { assertEquals(id, OperationalLogging.snapshot()?.get("requestId")) }
                        try { withContext(RequestLogging(GenerationLogging(requestId = "nested"))) { error("synthetic") } }
                        catch (_: IllegalStateException) { }
                        assertEquals(id, OperationalLogging.snapshot()?.get("requestId"))
                        val cancelled = launch { withContext(RequestLogging(GenerationLogging(requestId = "cancelled"))) { awaitCancellation() } }
                        yield(); cancelled.cancelAndJoin()
                        assertEquals(id, OperationalLogging.snapshot()?.get("requestId"))
                    }
                    assertEquals("outer", OperationalLogging.snapshot()?.get("requestId"))
                } }.awaitAll() }
            }
            assertEquals(original, OperationalLogging.snapshot())
            withContext(Dispatchers.IO) { assertNull(OperationalLogging.snapshot()?.get("requestId")) }
        } finally { OperationalLogging.restore(original) }
    }
}
