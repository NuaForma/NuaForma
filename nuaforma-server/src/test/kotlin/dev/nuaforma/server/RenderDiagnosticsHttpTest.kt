package dev.nuaforma.server

import com.fasterxml.jackson.databind.ObjectMapper
import dev.nuaforma.contracts.*
import dev.nuaforma.core.*
import dev.nuaforma.docx.pdf.*
import dev.nuaforma.pdf.PdfConformanceValidator
import dev.nuaforma.server.adapters.ktor.KtorServer
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RenderDiagnosticsHttpTest {
    @TempDir lateinit var temporary: Path

    private fun source(text: String, properties: String): ByteArray {
        val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        val relation = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        val parts = mapOf(
            "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>""",
            "_rels/.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="main" Type="$relation/officeDocument" Target="word/document.xml"/></Relationships>""",
            "word/_rels/document.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="styles" Type="$relation/styles" Target="styles.xml"/></Relationships>""",
            "word/styles.xml" to """<w:styles xmlns:w="$word"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:sz w:val="22"/></w:rPr></w:rPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style></w:styles>""",
            "word/document.xml" to """<w:document xmlns:w="$word"><w:body><w:p>$properties<w:r><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/></w:rPr><w:t>$text</w:t></w:r></w:p><w:sectPr><w:pgSz w:w="12240" w:h="15840"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>"""
        )
        return ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                parts.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
            }
        }.toByteArray()
    }

    @Test
    fun `Playground HTTP and correlated logs expose concrete PDF-A-3b render causes while production redacts them`() {
        val root = Path.of(System.getProperty("user.dir")).parent
        val font = NativePdfFont("Liberation Sans", "regular", Files.readAllBytes(root.resolve("nuaforma-pdf/src/test/resources/fonts/LiberationSans-Regular.ttf")))
        for (mode in LoggingMode.entries) testApplication {
            val events = java.util.Collections.synchronizedList(mutableListOf<GenerationLogEvent>())
            val provider = DocxPdfProvider(NativePdfConfiguration(listOf(font), PdfConformanceValidator { _, _ -> fail("Rendering must reject before validation") }))
            val registry = ProviderRegistry.registered(listOf(ProviderRegistration(ProviderMetadata("nuaforma-docx-pdf",
                DocxPdfProvider::class.java.name, "0.5.0-alpha.2", ContractVersion.CURRENT)) { provider }))
            application { KtorServer(registry, ServerConfiguration(ServerConfiguration(temporaryDirectory = temporary), mode), events::add).installRoutes(this) }
            val cases = listOf(
                Triple(source("Visible text", "<w:pPr><w:ind w:left=\"12000\"/></w:pPr>"), "CONTENT_OVERFLOW", "Paragraph indentation exceeds the physical page"),
                Triple(source("Visible text \uE000", ""), "MISSING_GLYPH", "The supplied font cannot encode requested text"))
            for ((source, failure, explanation) in cases) {
                val request = """{"input":{"format":"docx"},"output":{"format":"pdf","options":{"profile":"pdfa-3b"}}}"""
                val body = ByteArrayOutputStream()
                body.write("--RENDER\r\nContent-Disposition: form-data; name=\"request\"\r\nContent-Type: application/json\r\n\r\n$request\r\n--RENDER\r\nContent-Disposition: form-data; name=\"template\"; filename=\"source.docx\"\r\nContent-Type: application/vnd.openxmlformats-officedocument.wordprocessingml.document\r\n\r\n".toByteArray())
                body.write(source); body.write("\r\n--RENDER--\r\n".toByteArray())
                val response = client.post("/api/v1/documents") {
                    contentType(ContentType.MultiPart.FormData.withParameter("boundary", "RENDER")); setBody(body.toByteArray())
                }
                assertEquals(HttpStatusCode.UnprocessableEntity, response.status, response.bodyAsText())
                val requestId = response.headers["X-Request-ID"]
                assertFalse(requestId.isNullOrEmpty())
                assertTrue(events.any { it.requestId == requestId && it.code == "NATIVE_PDF_RENDER_STARTED" })
                assertTrue(events.any { it.requestId == requestId && it.code == "GENERATION_REJECTED" })
                val diagnostic = ObjectMapper().readTree(response.bodyAsText()).path("diagnostics").single()
                assertEquals("UNSUPPORTED_INPUT", diagnostic.path("code").asText())
                if (mode == LoggingMode.DEVELOPMENT) {
                    assertEquals("NATIVE_PDF_RENDER_REJECTED", diagnostic.path("reason").asText())
                    assertEquals("$failure: $explanation", diagnostic.path("sourceExplanation").asText())
                    assertFalse(diagnostic.has("sourceConstruct"))
                    assertFalse(diagnostic.has("sourcePath"))
                    assertFalse(diagnostic.has("documentLocation"))
                    assertTrue(events.any { it.requestId == requestId && it.code == "DOCUMENT_SOURCE_DETAIL" &&
                        it.text.contains(failure) && it.text.contains(explanation) })
                } else {
                    assertFalse(diagnostic.has("sourceExplanation"))
                    assertFalse(response.bodyAsText().contains(explanation))
                    assertTrue(events.none { it.requestId == requestId && it.text.contains(explanation) })
                    assertTrue(events.none { it.requestId == requestId && it.code == "DOCUMENT_SOURCE_DETAIL" })
                }
                assertFalse(response.headers[HttpHeaders.ContentType].orEmpty().contains("application/pdf"))
                Files.list(temporary).use { assertEquals(0L, it.count()) }
            }
        }
    }
}
