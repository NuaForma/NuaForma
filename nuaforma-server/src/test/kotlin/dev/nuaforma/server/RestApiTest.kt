package dev.nuaforma.server

import dev.nuaforma.contracts.*
import dev.nuaforma.core.*
import dev.nuaforma.server.adapters.ktor.KtorServer
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import io.ktor.server.testing.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.*

class RestApiTest {
    @TempDir lateinit var temporary: Path

    private val envelope = """{"input":{"format":"docx"},"output":{"format":"docx"},"providerId":"nuaforma-docx","data":{"name":"Ada","nested":{"a":[null,true,42]}},"options":{"watermark":{"text":"CONCEPT — {name}","color":"#A1B2C3","opacity":0.3,"rotation":-30}}}"""

    @Test
    fun `real DOCX generation preserves content and dynamic watermark with embedded parity`() = testApplication {
        val registry = ProviderRegistry.discover()
        application { KtorServer(registry, ServerConfiguration(temporaryDirectory = temporary)).installRoutes(this) }
        val response = client.post("/api/v1/documents") { contentType(ContentType.MultiPart.FormData.withParameter("boundary", "NFBOUNDARY")); setBody(multipart(envelope, template())) }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals("attachment; filename=\"document.docx\"", response.headers[HttpHeaders.ContentDisposition])
        val actual = entries(response.bodyAsBytes())
        assertTrue(actual.getValue("word/document.xml").contains("Hello "))
        assertTrue(actual.getValue("word/document.xml").contains("Ada"))
        assertFalse(actual.getValue("word/document.xml").contains("{name}"))
        assertTrue(actual.values.any { it.contains("CONCEPT") && it.contains("Ada") }, actual.keys.joinToString() + actual.values.filter { it.contains("CONCEPT") }.joinToString())
        val expected = ByteArrayOutputStream()
        registry.generate(GenerationRequest(template().inputStream(), GenerationRequirements(
            FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.DOCX),
            GenerationOptions(TextWatermark("CONCEPT — {name}", "#A1B2C3", 0.3, -30.0))),
            mapOf("name" to "Ada", "nested" to mapOf("a" to listOf(null, true, 42)))), expected, GenerationContext(), "nuaforma-docx")
        val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val shapeId = Regex("NFWatermark[0-9a-f]{32}")
        fun normalized(parts: Map<String, String>) = parts.mapKeys { uuid.replace(it.key, "UUID") }
            .mapValues { shapeId.replace(uuid.replace(it.value, "UUID"), "NFWatermarkUUID") }
        assertEquals(normalized(entries(expected.toByteArray())), normalized(actual))
        assertEmpty()
    }

    @Test
    fun `capabilities expose unavailable PDF and option metadata and assessment is deterministic`() = testApplication {
        application { KtorServer(ProviderRegistry.discover(), ServerConfiguration(temporaryDirectory = temporary)).installRoutes(this) }
        val caps = client.get("/api/v1/capabilities")
        assertEquals(HttpStatusCode.OK, caps.status)
        assertTrue(caps.bodyAsText().contains("PROVIDER_UNAVAILABLE"), caps.bodyAsText())
        assertTrue(caps.bodyAsText().contains("paragraphDefaults"))
        val accepted = client.post("/api/v1/assess") { contentType(ContentType.Application.Json); setBody(envelope) }
        assertEquals(HttpStatusCode.OK, accepted.status, accepted.bodyAsText())
        val ambiguous = client.post("/api/v1/assess") { contentType(ContentType.Application.Json); setBody(envelope.replace("\"providerId\":\"nuaforma-docx\",", "")) }
        assertEquals(HttpStatusCode.UnprocessableEntity, ambiguous.status)
        assertTrue(ambiguous.bodyAsText().contains("AMBIGUOUS_PROVIDER"))
        val pdf = client.post("/api/v1/assess") { contentType(ContentType.Application.Json); setBody("""{"input":{"format":"docx"},"output":{"format":"pdf"}}""") }
        assertEquals(HttpStatusCode.ServiceUnavailable, pdf.status)
    }

    @Test
    fun `invalid and unknown JSON cannot disclose confidential values`() = testApplication {
        application { KtorServer(ProviderRegistry.discover(), ServerConfiguration(temporaryDirectory = temporary)).installRoutes(this) }
        val invalid = listOf("not-json-PRIVATE", """{"input":{},"PRIVATE":true}""", envelope.replace("\"name\":\"Ada\"", "\"name\":\"PRIVATE\",\"name\":\"Ada\""),
            envelope.replace("\"name\":\"Ada\"", "\"name\":" + "[".repeat(70) + "0" + "]".repeat(70)),
            envelope.replace("\"format\":\"docx\"", "\"format\":null"), envelope.replace("\"providerId\":\"nuaforma-docx\"", "\"providerId\":\"PRIVATE/../\""))
        invalid.forEach { body ->
            val response = client.post("/api/v1/assess") { contentType(ContentType.Application.Json); setBody(body) }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertFalse(response.bodyAsText().contains("PRIVATE"))
        }
    }

    @Test
    fun `multipart errors and size bounds clean files without publishing partial output`() = testApplication {
        application { KtorServer(ProviderRegistry.discover(), ServerConfiguration(temporaryDirectory = temporary, maximumJsonBytes = 1024,
            maximumRequestBytes = 4096, maximumResourceBytes = 1024, generationLimits = GenerationLimits(maxTemplateBytes = 2048))).installRoutes(this) }
        val bodies = listOf(
            multipart(envelope, byteArrayOf(1, 2, 3)) to HttpStatusCode.UnprocessableEntity,
            multipart(envelope, ByteArray(2049)) to HttpStatusCode.PayloadTooLarge,
            multipart(envelope, byteArrayOf(1), duplicate = true) to HttpStatusCode.BadRequest,
            multipart(envelope, byteArrayOf(1), resourceName = "resource.../PRIVATE") to HttpStatusCode.BadRequest,
            multipart(" ".repeat(1025), byteArrayOf(1)) to HttpStatusCode.PayloadTooLarge,
        )
        bodies.forEach { (body, status) ->
            val response = client.post("/api/v1/documents") { contentType(ContentType.MultiPart.FormData.withParameter("boundary", "NFBOUNDARY")); setBody(body) }
            assertEquals(status, response.status, response.bodyAsText())
            assertNull(response.headers[HttpHeaders.ContentDisposition])
            assertEmpty()
        }
        val oversized = client.post("/api/v1/documents") { contentType(ContentType.MultiPart.FormData.withParameter("boundary", "NFBOUNDARY")); setBody(ByteArray(5000)) }
        assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status, oversized.bodyAsText())
        assertEmpty()
    }

    @Test
    fun `concurrent actual DOCX requests do not exchange data`() = testApplication {
        application { KtorServer(ProviderRegistry.discover(), ServerConfiguration(temporaryDirectory = temporary, maximumConcurrentRequests = 8)).installRoutes(this) }
        coroutineScope {
        val results = (0..5).map { index -> async {
            val response = client.post("/api/v1/documents") { contentType(ContentType.MultiPart.FormData.withParameter("boundary", "NFBOUNDARY")); setBody(multipart(envelope.replace("Ada", "Tenant$index"), template())) }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val text = entries(response.bodyAsBytes()).values.joinToString()
            assertTrue(text.contains("Tenant$index"))
            (0..5).filter { it != index }.forEach { assertFalse(text.contains("Tenant$it")) }
        } }
        results.awaitAll()
        }
        assertEmpty()
    }

    @Test
    fun `timeout interrupts running provider and removes staged partial output`() = testApplication {
        val entered = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val registry = ProviderRegistry.registered(listOf(ProviderRegistration(ProviderMetadata("test-provider", SlowProvider::class.java.name, "0.1.0", ContractVersion.CURRENT)) {
            SlowProvider(entered, exited)
        }))
        application { KtorServer(registry, ServerConfiguration(temporaryDirectory = temporary, requestTimeout = Duration.ofSeconds(3))).installRoutes(this) }
        val response = client.post("/api/v1/documents") { contentType(ContentType.MultiPart.FormData.withParameter("boundary", "NFBOUNDARY")); setBody(multipart(envelope.replace("nuaforma-docx", "test-provider"), template())) }
        assertEquals(HttpStatusCode.RequestTimeout, response.status, response.bodyAsText())
        assertTrue(entered.await(1, TimeUnit.SECONDS)); assertTrue(exited.await(1, TimeUnit.SECONDS))
        assertFalse(response.bodyAsText().contains("PRIVATE")); assertEmpty()
    }

    private fun assertEmpty() { Files.list(temporary).use { assertEquals(0, it.count()) } }

    @Test
    fun `total body budget also applies to chunked uploads without Content Length`() = testApplication {
        application { KtorServer(ProviderRegistry.discover(), ServerConfiguration(temporaryDirectory = temporary,
            maximumRequestBytes = 4096, maximumJsonBytes = 1024, maximumResourceBytes = 1024,
            generationLimits = GenerationLimits(maxTemplateBytes = 2048))).installRoutes(this) }
        val bytes = multipart(envelope, ByteArray(1024), resources = mapOf("a" to ByteArray(1000), "b" to ByteArray(1000), "c" to ByteArray(1000)))
        assertTrue(bytes.size > 4096)
        val response = client.post("/api/v1/documents") { setBody(object : OutgoingContent.WriteChannelContent() {
            override val contentType = ContentType.MultiPart.FormData.withParameter("boundary", "NFBOUNDARY")
            override suspend fun writeTo(channel: ByteWriteChannel) { channel.writeFully(bytes) }
        }) }
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status, response.bodyAsText()); assertEmpty()
    }

    @Test
    fun `unsupported options filenames and media types fail without a document`() = testApplication {
        application { KtorServer(ProviderRegistry.discover(), ServerConfiguration(temporaryDirectory = temporary)).installRoutes(this) }
        val unsupported = envelope.replace("\"output\":{\"format\":\"docx\"}", "\"output\":{\"format\":\"docx\",\"options\":{\"PRIVATE\":true}}")
        val request = client.post("/api/v1/documents") { contentType(ContentType.MultiPart.FormData.withParameter("boundary", "NFBOUNDARY")); setBody(multipart(unsupported, template())) }
        assertEquals(HttpStatusCode.UnprocessableEntity, request.status)
        assertFalse(request.bodyAsText().contains("PRIVATE")); assertNull(request.headers[HttpHeaders.ContentDisposition])
        val filename = client.post("/api/v1/assess") { contentType(ContentType.Application.Json); setBody(envelope.dropLast(1) + ",\"filename\":\"../../PRIVATE\"}") }
        assertEquals(HttpStatusCode.BadRequest, filename.status)
        val media = client.post("/api/v1/documents") { contentType(ContentType.Application.Json); setBody(envelope) }
        assertEquals(HttpStatusCode.UnsupportedMediaType, media.status)
        assertEmpty()
    }

    @Test
    fun `engine output budget is enforced before HTTP publication`() = testApplication {
        application { KtorServer(ProviderRegistry.discover(), ServerConfiguration(temporaryDirectory = temporary,
            generationLimits = GenerationLimits(maxOutputBytes = 1))).installRoutes(this) }
        val response = client.post("/api/v1/documents") { contentType(ContentType.MultiPart.FormData.withParameter("boundary", "NFBOUNDARY")); setBody(multipart(envelope, template())) }
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status, response.bodyAsText())
        assertNull(response.headers[HttpHeaders.ContentDisposition]); assertEmpty()
    }

    @Test
    fun `admission is bounded and abandoned client work ends by the server deadline`() = testApplication {
        val entered = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val registry = ProviderRegistry.registered(listOf(ProviderRegistration(ProviderMetadata("test-provider", SlowProvider::class.java.name, "0.1.0", ContractVersion.CURRENT)) { SlowProvider(entered, exited) }))
        application { KtorServer(registry, ServerConfiguration(temporaryDirectory = temporary, maximumConcurrentRequests = 1,
            requestTimeout = Duration.ofSeconds(5))).installRoutes(this) }
        coroutineScope {
            val running = async { client.post("/api/v1/documents") { contentType(ContentType.MultiPart.FormData.withParameter("boundary", "NFBOUNDARY")); setBody(multipart(envelope.replace("nuaforma-docx", "test-provider"), template())) } }
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            val busy = client.get("/api/v1/capabilities")
            assertEquals(HttpStatusCode.ServiceUnavailable, busy.status)
            assertEquals("1", busy.headers[HttpHeaders.RetryAfter])
            running.cancelAndJoin()
            assertTrue(withContext(Dispatchers.IO) { exited.await(7, TimeUnit.SECONDS) })
        }
        val ready = withTimeout(2000) {
            var response = client.get("/api/v1/capabilities")
            while (response.status == HttpStatusCode.ServiceUnavailable) { delay(10); response = client.get("/api/v1/capabilities") }
            response
        }
        assertEquals(HttpStatusCode.OK, ready.status, ready.bodyAsText()); assertEmpty()
    }

    private fun multipart(json: String, template: ByteArray, duplicate: Boolean = false, resourceName: String? = null, resources: Map<String, ByteArray> = emptyMap()): ByteArray = ByteArrayOutputStream().also { out ->
        fun part(name: String, type: String, bytes: ByteArray, filename: String? = null) {
            out.write(("--NFBOUNDARY\r\nContent-Disposition: form-data; name=\"$name\"" + (filename?.let { "; filename=\"$it\"" } ?: "") + "\r\nContent-Type: $type\r\n\r\n").toByteArray())
            out.write(bytes); out.write("\r\n".toByteArray())
        }
        part("request", "application/json", json.toByteArray(), "request.json")
        part("template", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", template, "../../PRIVATE.docx")
        if (duplicate) part("template", "application/octet-stream", template, "x.docx")
        resourceName?.let { part(it, "application/octet-stream", byteArrayOf(1), "font.ttf") }
        resources.forEach { (id, bytes) -> part("resource.$id", "application/octet-stream", bytes, "font.ttf") }
        out.write("--NFBOUNDARY--\r\n".toByteArray())
    }.toByteArray()

    private fun template(): ByteArray = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
        mapOf(
            "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""",
            "_rels/.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="r1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""",
            "word/document.xml" to """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Hello {name}</w:t></w:r></w:p><w:sectPr/></w:body></w:document>""",
        ).forEach { (name, content) -> zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry() }
    } }.toByteArray()

    private fun entries(bytes: ByteArray): Map<String, String> = linkedMapOf<String, String>().also { result -> ZipInputStream(bytes.inputStream()).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) { result[entry.name] = zip.readBytes().decodeToString(); entry = zip.nextEntry }
    } }
}

class SlowProvider(private val entered: CountDownLatch, private val exited: CountDownLatch) : DocumentProvider {
    override fun capabilities() = ProviderCapabilities(setOf(DocumentFormat.DOCX), setOf(DocumentFormat.DOCX))
    override fun availability() = ProviderAvailability(setOf(DocumentFormat.DOCX), setOf(DocumentFormat.DOCX))
    override fun assess(requirements: GenerationRequirements) = CapabilityAssessment(AssessmentStatus.SUPPORTED)
    override fun generate(request: GenerationRequest, output: OutputStream, context: GenerationContext): GenerationResult {
        entered.countDown()
        try { output.write("PRIVATE partial output".toByteArray()); while (true) { context.checkActive(); Thread.sleep(10) } }
        catch (_: InterruptedException) { throw GenerationException(listOf(Diagnostic(DiagnosticCodes.CANCELLED, "Cancelled test generation"))) }
        finally { exited.countDown() }
    }
}
