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
    fun `recovered mounted fonts retain activation HTTP catalog and DOCX availability after an empty restart`() {
        val directory = Files.createDirectory(temporary.resolve("fonts"))
        val fixture = Path.of(System.getProperty("nuaforma.repositoryRoot"), "nuaforma-docx/src/test/resources/fonts/LibreBarcode39-Regular.ttf")
        val valid = Files.copy(fixture, directory.resolve("valid.ttf"))
        Files.write(directory.resolve("rejected.otf"), byteArrayOf())
        val fontsType = Class.forName("dev.nuaforma.docx.DeploymentFonts")
        val providerType = Class.forName("dev.nuaforma.docx.DocxProvider").asSubclass(DocumentProvider::class.java)
        for (restarted in listOf(false, true)) testApplication {
            if (restarted) Files.write(valid, byteArrayOf())
            val fonts = fontsType.getMethod("load", Path::class.java).invoke(null, directory)
            val events = mutableListOf<GenerationLogEvent>()
            val registry = ProviderRegistry.registered(listOf(ProviderRegistration(ProviderMetadata("nuaforma-docx", providerType.name,
                "0.1.0", ContractVersion.CURRENT)) { providerType.getConstructor(fontsType).newInstance(fonts) }),
                GenerationLogging(LoggingMode.DEVELOPMENT, null, events::add))
            assertEquals(1, registry.providers.size)
            assertTrue(registry.diagnostics.isEmpty())
            assertTrue(events.any { it.code == "MOUNTED_FONT_REJECTED" && it.text.contains("rejected.otf") })
            assertTrue(events.any { it.code == "MOUNTED_FONT_REJECTION_DETAIL" && it.text.contains("exception=") })
            application { KtorServer(registry, ServerConfiguration(temporaryDirectory = temporary)).installRoutes(this) }
            val catalog = client.get("/api/v1/capabilities")
            assertEquals(HttpStatusCode.OK, catalog.status)
            val body = catalog.bodyAsText()
            assertEquals(!restarted, body.contains("Libre Barcode 39"))
            assertFalse(body.contains(directory.toString()) || body.contains("rejected.otf"))
            val response = client.post("/api/v1/documents") {
                contentType(ContentType.MultiPart.FormData.withParameter("boundary", "NFBOUNDARY"))
                setBody(multipart(envelope, template()))
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `real rejections explain synthetic templates in development and correlate safe production logs`() {
        for (mode in LoggingMode.entries) testApplication {
            val events = java.util.Collections.synchronizedList(mutableListOf<GenerationLogEvent>())
            application { KtorServer(ProviderRegistry.discover(), ServerConfiguration(ServerConfiguration(temporaryDirectory = temporary), mode), events::add).installRoutes(this) }
            val linked = entries(template()).toMutableMap().apply {
                this["_rels/.rels"] = getValue("_rels/.rels").replace("</Relationships>", """<Relationship Id="linked" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/attachedTemplate" Target="https://PRIVATE_TOKEN.example/template.dotm" TargetMode="External"/></Relationships>""")
            }
            val malformed = entries(template()).toMutableMap().apply { this["word/document.xml"] = "<PRIVATE_TOKEN broken" }
            val embedded = entries(template()).toMutableMap().apply {
                this["_rels/.rels"] = getValue("_rels/.rels").replace("</Relationships>", """<Relationship Id="embedded" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/oleObject" Target="PRIVATE_TOKEN"/></Relationships>""")
            }
            val cases = listOf(
                Triple(envelope, repack(linked), "Linked templates and embedded documents are not supported"),
                Triple(envelope, repack(embedded), "Linked templates and embedded documents are not supported"),
                Triple(envelope, repack(malformed), "A DOCX XML part is malformed"),
                Triple(envelope, "PRIVATE_TOKEN invalid ZIP".toByteArray(), "Input is not a valid unencrypted DOCX ZIP archive"),
                Triple(envelope, template("{absent}"), "Required template data is missing"),
                Triple(envelope, template("{#if name}yes{/if}"), "Template data has an incompatible type"),
                Triple(envelope, template("{#if}"), "Unexpected DOCX block marker"),
                Triple(envelope, template("Hello {name |}"), "Invalid template syntax"),
                Triple(envelope.replace("\"output\":{\"format\":\"docx\"}", """"output":{"format":"docx","options":{"fontMode":"validate","fonts":[{"family":"Synthetic Font","resource":"missing"}]}}"""), template(), "Supply exactly the declared font resources"),
                Triple(envelope.replace("\"output\":{\"format\":\"docx\"}", """"output":{"format":"docx","options":{"PRIVATE_TOKEN":true}}"""), template(), "Unrecognized DOCX options"),
            )
            for ((request, source, reason) in cases) {
                val response = client.post("/api/v1/documents?mode=development") {
                    header("NUAFORMA_MODE", "development"); header("X-NuaForma-Mode", "development")
                    contentType(ContentType.MultiPart.FormData.withParameter("boundary", "NFBOUNDARY"))
                    setBody(multipart(request.replace("\"name\":\"Ada\"", "\"name\":\"Ada\",\"NUAFORMA_MODE\":\"development\""), source))
                }
                val body = response.bodyAsText()
                assertEquals(HttpStatusCode.UnprocessableEntity, response.status, body)
                val id = requireNotNull(response.headers["X-Request-ID"])
                assertTrue(body.contains(id))
                assertFalse(body.contains("PRIVATE_TOKEN"), body)
                if (mode == LoggingMode.DEVELOPMENT) {
                    assertTrue(body.contains(reason), body)
                    assertTrue(body.contains("\"remediation\""), body)
                    if (reason.startsWith("A DOCX XML")) {
                        assertTrue(body.contains("\"part\":\"word/document.xml\""), body)
                        assertTrue(body.contains("xml:1:"), body)
                    }
                } else {
                    assertFalse(body.contains(reason), body)
                    assertFalse(body.contains("\"remediation\""), body)
                }
                val captured = synchronized(events) { events.filter { it.requestId == id && it.code == "GENERATION_REJECTED" } }
                assertTrue(captured.any { it.text.contains(reason) }, captured.joinToString { it.text })
                assertTrue(captured.none { it.text.contains("PRIVATE_TOKEN") })
                assertNull(response.headers[HttpHeaders.ContentDisposition])
                assertEmpty()
            }
        }
    }

    private fun repack(parts: Map<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> parts.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
        } }
    }.toByteArray()

    @Test
    fun `development traces real DOCX and watermark evaluation without exposing field values in errors`() = testApplication {
        val events = java.util.Collections.synchronizedList(mutableListOf<GenerationLogEvent>())
        val registry = ProviderRegistry.discover()
        application { KtorServer(registry, ServerConfiguration(ServerConfiguration(temporaryDirectory = temporary), LoggingMode.DEVELOPMENT), events::add).installRoutes(this) }
        val source = template("Hello {name} {password}")
        val json = envelope.replace("\"name\":\"Ada\"", "\"name\":\"SYNTHETIC_NAME\",\"password\":\"PRIVATE_PASSWORD\"")
        val response = client.post("/api/v1/documents") {
            contentType(ContentType.MultiPart.FormData.withParameter("boundary", "NFBOUNDARY")); setBody(multipart(json, source))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val output = entries(response.bodyAsBytes())
        assertTrue(output.getValue("word/document.xml").contains("SYNTHETIC_NAME"))
        assertTrue(output.getValue("word/document.xml").contains("PRIVATE_PASSWORD"))
        val capturedEvents = synchronized(events) { events.toList() }
        assertTrue(capturedEvents.any { it.development && it.text.contains("SYNTHETIC_NAME") })
        assertFalse(capturedEvents.any { it.text.contains("PRIVATE_PASSWORD") })
        val id = response.headers["X-Request-ID"]
        assertNotNull(id)
        val requestEvents = capturedEvents.filter { it.requestId != null }
        assertTrue(requestEvents.isNotEmpty()); assertTrue(requestEvents.all { it.requestId == id })
        assertTrue(capturedEvents.filter { it.requestId == null }.all { it.code in setOf("PROVIDER_READINESS", "SERVICE_READINESS_CHANGED") })
        val invalid = client.post("/api/v1/documents") {
            contentType(ContentType.MultiPart.FormData.withParameter("boundary", "NFBOUNDARY")); setBody(multipart(json, template("{absent}")))
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, invalid.status)
        assertFalse(invalid.bodyAsText().contains("SYNTHETIC_NAME"))
        assertTrue(invalid.bodyAsText().contains(requireNotNull(invalid.headers["X-Request-ID"])))
        assertEmpty()
    }

    @Test
    fun `standard image insertion has identical HTTP and embedded semantics and cleanup`() = testApplication {
        val registry = ProviderRegistry.discover()
        application { KtorServer(registry, ServerConfiguration(temporaryDirectory = temporary)).installRoutes(this) }
        val image = java.awt.image.BufferedImage(16, 8, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val bytes = ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "png", it) }.toByteArray()
        val encoded = java.util.Base64.getEncoder().encodeToString(bytes)
        val source = template("Before {picture | image(10, 5)} after {missing ?? null | image()}")
        val json = """{"input":{"format":"docx"},"output":{"format":"docx"},"providerId":"nuaforma-docx","data":{"picture":"$encoded"}}"""
        val response = client.post("/api/v1/documents") {
            contentType(ContentType.MultiPart.FormData.withParameter("boundary", "NFBOUNDARY")); setBody(multipart(json, source))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val expected = ByteArrayOutputStream()
        registry.generate(GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.DOCX)),
            mapOf("picture" to encoded)), expected, GenerationContext(), "nuaforma-docx")
        assertEquals(entries(expected.toByteArray()), entries(response.bodyAsBytes()))
        val rejected = client.post("/api/v1/documents") {
            contentType(ContentType.MultiPart.FormData.withParameter("boundary", "NFBOUNDARY"))
            setBody(multipart(json.replace(encoded, "PRIVATE-BAD-IMAGE"), source))
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, rejected.status)
        assertFalse(rejected.bodyAsText().contains("PRIVATE-BAD-IMAGE"))
        assertEmpty()
    }

    @Test
    fun `registered generator content has identical HTTP and embedded semantics with safe failures`() = testApplication {
        val pipe = object : ContentPipe {
            override val definition = ContentPipeDefinition("company", "Synthetic label", support = listOf(ContentPipeSupport(DocumentFormat.DOCX, DocumentFormat.DOCX)))
            override fun apply(value: Any?, arguments: List<Any?>, context: ContentPipeContext): ContentResult {
                context.checkActive()
                return ContentResult.Text(value as String)
            }
        }
        // Providers remain runtime-only dependencies of the transport, including this fixture.
        val providerType = Class.forName("dev.nuaforma.docx.DocxProvider").asSubclass(DocumentProvider::class.java)
        val fontsType = Class.forName("dev.nuaforma.docx.DeploymentFonts")
        val fonts = fontsType.getMethod("configured").invoke(null)
        val registry = ProviderRegistry.registered(listOf(ProviderRegistration(ProviderMetadata("nuaforma-docx",
            providerType.name, "0.1.0", ContractVersion.CURRENT)) {
            providerType.getConstructor(fontsType, List::class.java).newInstance(fonts, listOf(pipe))
        }))
        application { KtorServer(registry, ServerConfiguration(temporaryDirectory = temporary)).installRoutes(this) }
        val source = template("Hello {name | company()}")
        val json = """{"input":{"format":"docx"},"output":{"format":"docx"},"providerId":"nuaforma-docx","data":{"name":"{literal}"}}"""
        val response = client.post("/api/v1/documents") {
            contentType(ContentType.MultiPart.FormData.withParameter("boundary", "NFBOUNDARY")); setBody(multipart(json, source))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val expected = ByteArrayOutputStream()
        registry.generate(GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.DOCX)),
            mapOf("name" to "{literal}")), expected, GenerationContext())
        assertEquals(entries(expected.toByteArray()), entries(response.bodyAsBytes()))
        val rejected = client.post("/api/v1/documents") {
            contentType(ContentType.MultiPart.FormData.withParameter("boundary", "NFBOUNDARY"))
            setBody(multipart(json, template("{name | company() | company()}")))
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, rejected.status)
        assertFalse(rejected.bodyAsText().contains("{literal}"))
        assertEmpty()
    }

    @Test
    fun `string functions and null fallback use identical embedded and REST document and watermark semantics`() = testApplication {
        val registry = ProviderRegistry.discover()
        application { KtorServer(registry, ServerConfiguration(temporaryDirectory = temporary)).installRoutes(this) }
        val watermark = "CONCEPT — {trim(name)}"
        val json = envelope.replace("\"name\":\"Ada\"", "\"name\":\" Ada \"")
            .replace("CONCEPT — {name}", watermark)
        val source = template("{#if equalsIgnoreCase(trim(name), &quot;ada&quot;)}Hello {trim(name)}{/if} {#if trim(absent ?? &quot;&quot;) == &quot;&quot;}No missing value{/if}")
        val response = client.post("/api/v1/documents") {
            contentType(ContentType.MultiPart.FormData.withParameter("boundary", "NFBOUNDARY"))
            setBody(multipart(json, source))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val actual = entries(response.bodyAsBytes())
        val output = ByteArrayOutputStream()
        registry.generate(GenerationRequest(source.inputStream(), GenerationRequirements(
            FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.DOCX),
            GenerationOptions(TextWatermark(watermark, "#A1B2C3", 0.3, -30.0))),
            mapOf("name" to " Ada ", "nested" to mapOf("a" to listOf(null, true, 42)))), output, GenerationContext(), "nuaforma-docx")
        assertEquals(entries(output.toByteArray()).getValue("word/document.xml"), actual.getValue("word/document.xml"))
        assertTrue(actual.getValue("word/document.xml").contains("No missing value"))
        assertTrue(actual.values.any { it.contains("CONCEPT — Ada") })
        assertFalse(actual.values.any { it.contains("{trim") || it.contains("{#if") })
        assertEmpty()
    }

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
        assertTrue(caps.bodyAsText().contains("\"fonts\":{\"status\":\"AVAILABLE\",\"entries\":[]}"), caps.bodyAsText())
        assertTrue(caps.bodyAsText().contains("\"fonts\":{\"status\":\"UNAVAILABLE\",\"entries\":[]}"), caps.bodyAsText())
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

    private fun template(text: String = "Hello {name}"): ByteArray = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
        mapOf(
            "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""",
            "_rels/.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="r1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""",
            "word/document.xml" to """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>$text</w:t></w:r></w:p><w:sectPr/></w:body></w:document>""",
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
