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

class MissingFontDiagnosticsHttpTest {
    @TempDir lateinit var temporary: Path

    private fun source(): ByteArray {
        val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        val relation = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        val parts = mapOf(
            "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>""",
            "_rels/.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="main" Type="$relation/officeDocument" Target="word/document.xml"/></Relationships>""",
            "word/_rels/document.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="styles" Type="$relation/styles" Target="styles.xml"/></Relationships>""",
            "word/styles.xml" to """<w:styles xmlns:w="$word"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:sz w:val="22"/></w:rPr></w:rPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style></w:styles>""",
            "word/document.xml" to """<w:document xmlns:w="$word"><w:body><w:p><w:r><w:rPr><w:rFonts w:ascii="Missing Playground Font" w:hAnsi="Missing Playground Font"/><w:b/><w:i/></w:rPr><w:t>Visible source</w:t></w:r></w:p><w:sectPr><w:pgSz w:w="12240" w:h="15840"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>"""
        )
        return ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                parts.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
            }
        }.toByteArray()
    }

    @Test
    fun `Playground mode exposes the missing face and location while production responses and logs redact them`() {
        val root = Path.of(System.getProperty("user.dir")).parent
        val font = NativePdfFont("Liberation Sans", "regular", Files.readAllBytes(root.resolve("nuaforma-pdf/src/test/resources/fonts/LiberationSans-Regular.ttf")))
        for (mode in LoggingMode.entries) testApplication {
            val events = java.util.Collections.synchronizedList(mutableListOf<GenerationLogEvent>())
            val provider = DocxPdfProvider(NativePdfConfiguration(listOf(font), PdfConformanceValidator { _, _ -> fail("Must reject before rendering") }))
            val registry = ProviderRegistry.registered(listOf(ProviderRegistration(ProviderMetadata("nuaforma-docx-pdf",
                DocxPdfProvider::class.java.name, "0.5.0-alpha.1", ContractVersion.CURRENT)) { provider }))
            application { KtorServer(registry, ServerConfiguration(ServerConfiguration(temporaryDirectory = temporary), mode), events::add).installRoutes(this) }
            val request = """{"input":{"format":"docx"},"output":{"format":"pdf","options":{"profile":"standard"}}}"""
            val body = ByteArrayOutputStream()
            body.write("--FONT\r\nContent-Disposition: form-data; name=\"request\"\r\nContent-Type: application/json\r\n\r\n$request\r\n--FONT\r\nContent-Disposition: form-data; name=\"template\"; filename=\"source.docx\"\r\nContent-Type: application/vnd.openxmlformats-officedocument.wordprocessingml.document\r\n\r\n".toByteArray())
            body.write(source()); body.write("\r\n--FONT--\r\n".toByteArray())
            val response = client.post("/api/v1/documents") {
                contentType(ContentType.MultiPart.FormData.withParameter("boundary", "FONT")); setBody(body.toByteArray())
            }
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status, response.bodyAsText())
            val diagnostic = ObjectMapper().readTree(response.bodyAsText()).path("diagnostics").single()
            if (mode == LoggingMode.DEVELOPMENT) {
                assertEquals("Missing font family: Missing Playground Font; variant: boldItalic", diagnostic.path("sourceExplanation").asText())
                assertEquals("word/document.xml", diagnostic.path("part").asText())
                assertEquals("paragraph:1", diagnostic.path("documentLocation").asText())
                assertTrue(events.any { it.code == "DOCUMENT_SOURCE_DETAIL" && it.text.contains("Missing Playground Font") && it.text.contains("boldItalic") })
            } else {
                assertFalse(diagnostic.has("sourceExplanation"))
                assertFalse(response.bodyAsText().contains("Missing Playground Font"))
                assertTrue(events.none { it.text.contains("Missing Playground Font") || it.text.contains("Visible source") })
            }
            assertFalse(response.headers[HttpHeaders.ContentType].orEmpty().contains("application/pdf"))
            Files.list(temporary).use { assertEquals(0L, it.count()) }
        }
    }
}
