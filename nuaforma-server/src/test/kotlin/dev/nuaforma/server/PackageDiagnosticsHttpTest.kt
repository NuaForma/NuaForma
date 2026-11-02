package dev.nuaforma.server

import com.fasterxml.jackson.databind.ObjectMapper
import dev.nuaforma.contracts.*
import dev.nuaforma.core.*
import dev.nuaforma.server.adapters.ktor.KtorServer
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path

class PackageDiagnosticsHttpTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `development HTTP and correlated operational logs identify the rejected package entry`() {
        for (mode in LoggingMode.entries) testApplication {
            val events = java.util.Collections.synchronizedList(mutableListOf<GenerationLogEvent>())
            val registry = ProviderRegistry.registered(listOf(ProviderRegistration(ProviderMetadata("test-package",
                PackageRejectionProvider::class.java.name, "0.1.0", ContractVersion.CURRENT)) { PackageRejectionProvider() }))
            application {
                KtorServer(registry, ServerConfiguration(ServerConfiguration(temporaryDirectory = temporary), mode), events::add)
                    .installRoutes(this)
            }
            val request = """{"providerId":"test-package","input":{"format":"docx"},"output":{"format":"pdf"}}"""
            val body = "--NF190\r\nContent-Disposition: form-data; name=\"request\"\r\nContent-Type: application/json\r\n\r\n$request\r\n" +
                "--NF190\r\nContent-Disposition: form-data; name=\"template\"; filename=\"synthetic.docx\"\r\n" +
                "Content-Type: application/vnd.openxmlformats-officedocument.wordprocessingml.document\r\n\r\nsynthetic\r\n--NF190--\r\n"
            val response = client.post("/api/v1/documents") {
                contentType(ContentType.MultiPart.FormData.withParameter("boundary", "NF190"))
                setBody(body.toByteArray())
            }
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status, response.bodyAsText())
            val json = ObjectMapper().readTree(response.bodyAsText())
            val diagnostic = json.path("diagnostics").single()
            val id = requireNotNull(response.headers["X-Request-ID"])
            assertEquals(id, json.path("requestId").asText())
            val rejections = synchronized(events) { events.filter { it.code == "GENERATION_REJECTED" && it.requestId == id } }
            assertFalse(rejections.isEmpty())
            if (mode == LoggingMode.DEVELOPMENT) {
                assertEquals("word/media/image1.jpeg", diagnostic.path("part").asText())
                assertEquals("Unsupported package part", diagnostic.path("sourceExplanation").asText())
                assertTrue(rejections.all { it.text.contains("word/media/image1.jpeg") })
            } else {
                assertFalse(diagnostic.has("part"))
                assertTrue(rejections.all { it.text.contains("[other package part]") })
                assertFalse(response.bodyAsText().contains("image1.jpeg"))
            }
            Files.list(temporary).use { assertEquals(0L, it.count()) }
        }
    }

    class PackageRejectionProvider : DocumentProvider {

        override fun capabilities() = ProviderCapabilities(setOf(DocumentFormat.DOCX), setOf(DocumentFormat.PDF))

        override fun availability() = ProviderAvailability(setOf(DocumentFormat.DOCX), setOf(DocumentFormat.PDF))

        override fun assess(requirements: GenerationRequirements) = CapabilityAssessment(AssessmentStatus.SUPPORTED)

        override fun generate(request: GenerationRequest, output: OutputStream, context: GenerationContext): GenerationResult {
            throw GenerationException(listOf(Diagnostic(DiagnosticCodes.UNSUPPORTED_INPUT, RejectionReason.NATIVE_DOCX_PROFILE_UNSUPPORTED,
                part = "word/media/image1.jpeg").withSourceContext(DocumentSourceContext("Unsupported package part", null, null, ""))))
        }
    }
}
