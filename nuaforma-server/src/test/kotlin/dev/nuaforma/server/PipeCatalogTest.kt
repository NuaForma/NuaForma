package dev.nuaforma.server

import com.fasterxml.jackson.databind.ObjectMapper
import dev.nuaforma.contracts.*
import dev.nuaforma.core.ProviderRegistry
import dev.nuaforma.server.adapters.ktor.KtorServer
import dev.nuaforma.server.internal.PipeCatalog
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.OutputStream
import java.nio.file.Path

class PipeCatalogTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `real provider catalogs preserve fields and distinguish declared from currently usable image pairs`() = testApplication {
        val registry = ProviderRegistry.discover()
        application { KtorServer(registry, ServerConfiguration(temporaryDirectory = temporary)).installRoutes(this) }
        val response = client.get("/api/v1/capabilities")
        assertEquals(HttpStatusCode.OK, response.status)
        val json = ObjectMapper().readTree(response.bodyAsText())
        for (provider in json.path("providers")) {
            assertTrue(provider.has("fonts")); assertTrue(provider.has("declared")); assertTrue(provider.has("available"))
            assertEquals("AVAILABLE", provider.path("valuePipes").path("status").asText())
            assertTrue(provider.path("valuePipes").path("entries").isEmpty)
            val image = provider.path("contentPipes").path("entries").single { it.path("name").asText() == "image" }
            assertFalse(image.path("chainable").asBoolean())
            assertEquals(setOf("image/jpeg", "image/png"), image.path("contentTypes").map { it.asText() }.toSet())
            assertEquals(listOf("maxWidthMm", "maxHeightMm", "keepRatio"), image.path("arguments").map { it.path("name").asText() })
            assertEquals(listOf("inline"), image.path("contexts").map { it.asText() })
            assertFalse(image.path("missingAsNull").asBoolean())
            val rich = provider.path("contentPipes").path("entries").single { it.path("name").asText() == "richText" }
            assertTrue(rich.path("missingAsNull").asBoolean())
            assertEquals(listOf("application/rtf"), rich.path("contentTypes").map { it.asText() })
            assertEquals(listOf("format", "trimStart", "trimEnd", "inline"), rich.path("arguments").map { it.path("name").asText() })
            assertEquals(listOf(true, true, true), rich.path("arguments").drop(1).map { it.path("defaultValue").asBoolean() })
            val writes = provider.path("available").path("writeFormats").map { it.asText() }.toSet()
            assertTrue(image.path("availableSupport").all { it.path("output").asText() in writes })
            assertTrue(image.path("declaredSupport").any { it.path("output").asText() == "docx" })
            if (provider.path("id").asText() == "nuaforma-docx-pdf") {
                assertTrue(image.path("declaredSupport").any { it.path("output").asText() == "pdf" })
                assertEquals("pdf" in writes, image.path("availableSupport").any { it.path("output").asText() == "pdf" })
            }
        }
        assertFalse(response.bodyAsText().contains("dev.nuaforma.docx."))
    }

    @Test
    fun `metadata snapshots use actual facet declarations defaults and profile support without generation`() {
        val fixture = Fixture()
        val result = ObjectMapper().valueToTree<com.fasterxml.jackson.databind.JsonNode>(
            PipeCatalog.describe(fixture, fixture.capabilities(), fixture.availability(), GenerationContext()))
        assertEquals("select", result.path("valuePipes").path("entries").single().path("name").asText())
        assertTrue(result.path("valuePipes").path("entries").single().path("chainable").asBoolean())
        assertEquals("fallback", result.path("valuePipes").path("entries").single().path("arguments").single().path("defaultValue").asText())
        val image = result.path("contentPipes").path("entries").single()
        assertEquals(2, image.path("declaredSupport").size())
        assertEquals(1, image.path("availableSupport").size())
        assertEquals("docx", image.path("availableSupport").single().path("output").asText())
    }

    @Test
    fun `unsupported empty invalid colliding and failing facets are explicit and sanitized`() {
        val mapper = ObjectMapper()
        val plain = object : DocumentProvider by Fixture() {}
        val unsupported = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(
            PipeCatalog.describe(plain, plain.capabilities(), plain.availability(), GenerationContext()))
        assertEquals("UNSUPPORTED", unsupported.path("valuePipes").path("status").asText())
        for (fixture in listOf(Fixture(collision = true), Fixture(fail = true), Fixture(invalidSupport = true))) {
            val text = mapper.writeValueAsString(PipeCatalog.describe(fixture, fixture.capabilities(), fixture.availability(), GenerationContext()))
            val tree = mapper.readTree(text)
            assertEquals("UNAVAILABLE", tree.path("valuePipes").path("status").asText())
            assertEquals("UNAVAILABLE", tree.path("contentPipes").path("status").asText())
            assertTrue(tree.path("contentPipes").path("entries").isEmpty)
            assertFalse(text.contains("PRIVATE")); assertFalse(text.contains("Fixture"))
        }
        assertThrows(GenerationException::class.java) {
            PipeCatalog.describe(Fixture(), Fixture().capabilities(), Fixture().availability(),
                GenerationContext(cancellation = CancellationToken { true }))
        }
    }

    private class Fixture(val collision: Boolean = false, val fail: Boolean = false, val invalidSupport: Boolean = false) :
        DocumentProvider, TemplatePipeProvider, ContentPipeProvider {
        override fun capabilities() = ProviderCapabilities(setOf(DocumentFormat.DOCX), setOf(DocumentFormat.DOCX, DocumentFormat.PDF))
        override fun availability() = ProviderAvailability(setOf(DocumentFormat.DOCX), setOf(DocumentFormat.DOCX))
        override fun assess(requirements: GenerationRequirements) = CapabilityAssessment(AssessmentStatus.SUPPORTED)
        override fun generate(request: GenerationRequest, output: OutputStream, context: GenerationContext): GenerationResult =
            error("Metadata must not generate documents")
        override fun templatePipes(): List<TemplatePipeDefinition> {
            if (fail) error("PRIVATE path or credential")
            return listOf(TemplatePipeDefinition(if (collision) "image" else "select", "Select a member",
                listOf(OptionDefinition("key", OptionType.STRING, "Member", defaultValue = "fallback"))))
        }
        override fun contentPipes() = listOf(ContentPipeDefinition("image", "Synthetic image", support = listOf(
            ContentPipeSupport(if (invalidSupport) DocumentFormat("other") else DocumentFormat.DOCX, DocumentFormat.DOCX),
            ContentPipeSupport(DocumentFormat.DOCX, DocumentFormat.PDF, setOf("pdfa-3b")))))
    }
}
