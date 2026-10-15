package dev.nuaforma.server

import dev.nuaforma.contracts.*
import dev.nuaforma.core.*
import dev.nuaforma.server.adapters.ktor.KtorServer
import dev.nuaforma.server.internal.ServiceHealth
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class HealthTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `health reads are lightweight snapshots with expiration draining and recovery`() {
        val provider = Fixture()
        val now = AtomicLong(0)
        ServiceHealth(registry(provider), refreshMillis = 10_000, maximumAgeMillis = 20_000, nanoTime = now::get).use { health ->
            assertTrue(health.live()); assertFalse(health.ready())
            health.start()
            eventually { health.ready() }
            val calls = provider.calls.get()
            repeat(10_000) { assertTrue(health.live()); assertTrue(health.ready()) }
            assertEquals(calls, provider.calls.get())
            now.set(java.util.concurrent.TimeUnit.SECONDS.toNanos(21))
            assertFalse(health.ready())
            health.drain(); assertFalse(health.ready()); assertTrue(health.live())
        }
        ServiceHealth(registry(provider), refreshMillis = 10, maximumAgeMillis = 1000).use { health ->
            provider.usable = false
            health.start()
            eventually { provider.calls.get() > 2 }
            assertFalse(health.ready())
            provider.usable = true
            eventually { health.ready() }
        }
        ServiceHealth(ProviderRegistry.registered(emptyList())).use { health ->
            health.start(); assertFalse(health.ready()); health.close(); assertFalse(health.live())
        }
    }

    @Test
    fun `missing PDF leaves DOCX ready and health responses contain no diagnostic details`() = testApplication {
        val events = java.util.Collections.synchronizedList(mutableListOf<GenerationLogEvent>())
        application { KtorServer(ProviderRegistry.discover(), ServerConfiguration(temporaryDirectory = temporary), events::add).installRoutes(this) }
        val live = client.get("/health/live")
        assertEquals(HttpStatusCode.OK, live.status)
        assertEquals("{\"status\":\"UP\"}", live.bodyAsText())
        var ready = client.get("/health/ready")
        withTimeout(5000) { while (ready.status != HttpStatusCode.OK) { delay(10); ready = client.get("/health/ready") } }
        assertEquals("{\"status\":\"UP\"}", ready.bodyAsText())
        assertNotNull(ready.headers["X-Request-ID"])
        assertEquals("no-store", ready.headers[HttpHeaders.CacheControl])
        repeat(20) { assertEquals(HttpStatusCode.OK, client.get("/health/ready").status) }
        assertTrue(events.none { it.code == "REQUEST_STARTED" || it.code == "REQUEST_FINISHED" })
    }

    @Test
    fun `health remains responsive while every generation slot is occupied`() = testApplication {
        val release = java.util.concurrent.CountDownLatch(1)
        val provider = Fixture(release)
        application { KtorServer(registry(provider), ServerConfiguration(temporaryDirectory = temporary, maximumConcurrentRequests = 1)).installRoutes(this) }
        val body = "--HEALTHBOUNDARY\r\nContent-Disposition: form-data; name=\"request\"\r\nContent-Type: application/json\r\n\r\n" +
            "{\"input\":{\"format\":\"docx\"},\"output\":{\"format\":\"docx\"},\"providerId\":\"synthetic\"}\r\n" +
            "--HEALTHBOUNDARY\r\nContent-Disposition: form-data; name=\"template\"; filename=\"synthetic\"\r\nContent-Type: application/octet-stream\r\n\r\nfixture\r\n--HEALTHBOUNDARY--\r\n"
        try {
            coroutineScope {
                val generation = async { client.post("/api/v1/documents") {
                    contentType(ContentType.MultiPart.FormData.withParameter("boundary", "HEALTHBOUNDARY")); setBody(body)
                } }
                assertTrue(withContext(Dispatchers.IO) { provider.started.await(5, java.util.concurrent.TimeUnit.SECONDS) })
                assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/api/v1/version").status)
                assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
                assertEquals(HttpStatusCode.OK, client.get("/health/ready").status)
                release.countDown()
                assertEquals(HttpStatusCode.OK, generation.await().status)
            }
        } finally { release.countDown() }
    }

    @Test
    fun `no providers keeps liveness up and readiness safely down`() = testApplication {
        application { KtorServer(ProviderRegistry.registered(emptyList()), ServerConfiguration(temporaryDirectory = temporary)).installRoutes(this) }
        assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
        val ready = client.get("/health/ready")
        assertEquals(HttpStatusCode.ServiceUnavailable, ready.status)
        assertEquals("{\"status\":\"DOWN\"}", ready.bodyAsText())
        assertNotNull(ready.headers["X-Request-ID"])
    }

    private fun registry(provider: Fixture): ProviderRegistry = ProviderRegistry.registered(listOf(
        ProviderRegistration(ProviderMetadata("synthetic", Fixture::class.java.name, "0.1.0", ContractVersion.CURRENT)) { provider }))

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(condition())
    }

    private class Fixture(private val block: java.util.concurrent.CountDownLatch? = null) : DocumentProvider {
        val calls = AtomicInteger()
        val started = java.util.concurrent.CountDownLatch(1)
        @Volatile var usable = true
        override fun capabilities() = ProviderCapabilities(setOf(DocumentFormat.DOCX), setOf(DocumentFormat.DOCX))
        override fun availability(): ProviderAvailability {
            calls.incrementAndGet()
            return if (usable) ProviderAvailability(setOf(DocumentFormat.DOCX), setOf(DocumentFormat.DOCX))
            else ProviderAvailability(emptySet(), emptySet(), listOf(Diagnostic(DiagnosticCodes.PROVIDER_UNAVAILABLE, "PRIVATE detail")))
        }
        override fun assess(requirements: GenerationRequirements) = CapabilityAssessment(AssessmentStatus.SUPPORTED)
        override fun generate(request: GenerationRequest, output: OutputStream, context: GenerationContext): GenerationResult {
            val release = requireNotNull(block) { "Health must not generate" }
            started.countDown()
            while (!release.await(10, java.util.concurrent.TimeUnit.MILLISECONDS)) context.checkActive()
            output.write("synthetic".toByteArray())
            return GenerationResult(request.requirements.output)
        }
    }
}
