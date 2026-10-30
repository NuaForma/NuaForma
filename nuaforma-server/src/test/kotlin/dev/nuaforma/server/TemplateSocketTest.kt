package dev.nuaforma.server

import dev.nuaforma.contracts.*
import dev.nuaforma.core.*
import dev.nuaforma.docx.pdf.*
import dev.nuaforma.pdf.PdfProcessValidator
import dev.nuaforma.server.adapters.ktor.KtorServer
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

class TemplateSocketTest {
    @TempDir lateinit var temporary: Path

    @Test fun `actual HTTP socket renders mixed quote dates and correlates development syntax diagnostics`() {
        val root = Path.of(System.getProperty("nuaforma.repositoryRoot"))
        val fonts = listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic", "boldItalic" to "BoldItalic").map { (face, file) ->
            NativePdfFont("Liberation Sans", face, Files.readAllBytes(root.resolve("nuaforma-pdf/src/test/resources/fonts/LiberationSans-$file.ttf")))
        }
        val validator = PdfProcessValidator(Path.of(System.getProperty("nuaforma.pdf.validation.java")),
            Files.list(Path.of(System.getProperty("nuaforma.pdf.validation"))).use { paths -> paths.filter { it.toString().endsWith(".jar") }.sorted().toList() }, temporary)
        val provider = DocxPdfProvider(NativePdfConfiguration(fonts, validator))
        val source = Files.readAllBytes(root.resolve("nuaforma-docx-pdf/src/test/resources/native-profile/word-letter.docx"))
        for (mode in LoggingMode.entries) {
            val events = java.util.Collections.synchronizedList(mutableListOf<GenerationLogEvent>())
            val registry = ProviderRegistry.registered(listOf(ProviderRegistration(ProviderMetadata("nuaforma-docx-pdf",
                DocxPdfProvider::class.java.name, "0.3.0-alpha.2", ContractVersion.CURRENT)) { provider }))
            val adapter = KtorServer(registry, ServerConfiguration(ServerConfiguration(temporaryDirectory = temporary), mode), events::add)
            val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { adapter.installRoutes(this) }.start()
            try {
                val port = runBlocking { server.engine.resolvedConnectors().single().port }
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().use { client ->
                    val uri = URI("http://127.0.0.1:$port/api/v1/documents")
                    fun send(marker: String, path: String = "/api/v1/documents"): HttpResponse<ByteArray> {
                        val template = insert(source, marker)
                        val envelope = """{"input":{"format":"docx"},"output":{"format":"pdf","options":{"profile":"standard"}},"data":{"day":"2026-10-28","name":"Ada"}}"""
                        val body = ByteArrayOutputStream()
                        for ((name, media, bytes) in listOf(Triple("request", "application/json", envelope.toByteArray()), Triple("template", "application/octet-stream", template))) {
                            body.write("--SYNTAX144\r\nContent-Disposition: form-data; name=\"$name\"; filename=\"synthetic\"\r\nContent-Type: $media\r\n\r\n".toByteArray())
                            body.write(bytes); body.write("\r\n".toByteArray())
                        }
                        body.write("--SYNTAX144--\r\n".toByteArray())
                        return client.send(HttpRequest.newBuilder(uri.resolve(path)).timeout(Duration.ofSeconds(90))
                            .header("Content-Type", "multipart/form-data; boundary=SYNTAX144")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofByteArray())
                    }
                    val success = send("{day | date(“dd-MM-yyyy')}")
                    assertEquals(200, success.statusCode(), if (success.statusCode() == 200) "" else success.body().decodeToString())
                    Loader.loadPDF(success.body()).use { pdf ->
                        assertTrue(PDFTextStripper().getText(pdf).contains("28-10-2026"))
                        assertTrue(pdf.getPage(0).resources.fontNames.all { pdf.getPage(0).resources.getFont(it).isEmbedded })
                    }
                    val hiddenMarker = "{name}</w:t></w:r><w:r><w:rPr><w:vanish/></w:rPr><w:t>{HIDDEN_LITERAL"
                    val hidden = send(hiddenMarker)
                    assertEquals(200, hidden.statusCode(), if (hidden.statusCode() == 200) "" else hidden.body().decodeToString())
                    Loader.loadPDF(hidden.body()).use { pdf ->
                        val text = PDFTextStripper().getText(pdf)
                        assertTrue(text.contains("Ada"), text)
                        assertFalse(text.contains("HIDDEN_LITERAL"), text)
                    }
                    val rejected = send("{trim(name,)}")
                    assertEquals(422, rejected.statusCode())
                    val json = com.fasterxml.jackson.databind.ObjectMapper().readTree(rejected.body())
                    val diagnostic = json.path("diagnostics").single()
                    val id = rejected.headers().firstValue("X-Request-ID").orElseThrow()
                    assertEquals(id, json.path("requestId").asText())
                    val captured = synchronized(events) { events.filter { it.requestId == id } }
                    assertTrue(captured.any { it.code == "GENERATION_REJECTED" && it.text.contains("UNEXPECTED_EXPRESSION_TOKEN") })
                    if (mode == LoggingMode.DEVELOPMENT) {
                        assertEquals("{trim(name,)}", diagnostic.path("expression").asText())
                        assertEquals("paragraph:1", diagnostic.path("documentLocation").asText())
                        assertTrue(captured.any { it.code == "TEMPLATE_SYNTAX_DETAIL" && it.text.contains("trim(name,)") })
                    } else {
                        assertFalse(diagnostic.has("expression"))
                        assertTrue(captured.none { it.text.contains("trim(name,)") })
                    }
                    val marked = send("Prefix {broken", "/api/v1/diagnostics/template")
                    if (mode == LoggingMode.DEVELOPMENT) {
                        assertEquals(200, marked.statusCode(), if (marked.statusCode() == 200) "" else marked.body().decodeToString())
                        assertEquals("template-error", marked.headers().firstValue("NuaForma-Diagnostic-Artifact").orElseThrow())
                        assertEquals("no-store", marked.headers().firstValue("Cache-Control").orElseThrow())
                        Loader.loadPDF(marked.body()).use { pdf ->
                            val text = PDFTextStripper().getText(pdf)
                            assertTrue(text.contains("[ERROR 1] {broken"), text)
                            assertTrue(text.contains("TEMPLATE DIAGNOSTIC - ERROR 1"), text)
                            assertTrue(text.contains("UNCLOSED_TEMPLATE_MARKER"), text)
                            assertTrue(pdf.numberOfPages >= 2)
                        }
                        val hiddenValid = send(hiddenMarker, "/api/v1/diagnostics/template")
                        assertEquals(422, hiddenValid.statusCode())
                        assertTrue(hiddenValid.body().decodeToString().contains("TEMPLATE_DIAGNOSTIC_REQUIRES_PARSER_ERROR"))
                        val valid = send("{name}", "/api/v1/diagnostics/template")
                        assertEquals(422, valid.statusCode())
                        assertTrue(valid.body().decodeToString().contains("TEMPLATE_DIAGNOSTIC_REQUIRES_PARSER_ERROR"))
                        val interrupted = send("{broken</w:t><w:br/><w:t>}", "/api/v1/diagnostics/template")
                        assertEquals(422, interrupted.statusCode())
                        assertTrue(interrupted.body().decodeToString().contains("NATIVE_DOCX_PROFILE_UNSUPPORTED"))
                        assertFalse(interrupted.headers().firstValue("Content-Type").orElse("").contains("application/pdf"))
                    } else {
                        assertEquals(404, marked.statusCode())
                        assertFalse(marked.body().decodeToString().contains("{broken"))
                    }
                }
            } finally { server.stop(1000, 5000) }
        }
        assertEquals(0, Files.list(temporary).use { it.count() })
    }

    private fun insert(source: ByteArray, marker: String): ByteArray = ByteArrayOutputStream().also { result ->
        ZipOutputStream(result).use { output -> ZipInputStream(source.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val bytes = input.readBytes()
                output.putNextEntry(ZipEntry(entry.name))
                output.write(if (entry.name == "word/document.xml") bytes.decodeToString().replace("<w:body>",
                    "<w:body><w:p><w:r><w:rPr><w:rFonts w:ascii=\"Liberation Sans\" w:hAnsi=\"Liberation Sans\"/></w:rPr><w:t>$marker</w:t></w:r></w:p>").toByteArray() else bytes)
                output.closeEntry()
            }
        } }
    }.toByteArray()
}
