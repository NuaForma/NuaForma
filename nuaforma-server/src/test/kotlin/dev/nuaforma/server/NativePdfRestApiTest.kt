package dev.nuaforma.server

import dev.nuaforma.contracts.*
import dev.nuaforma.core.*
import dev.nuaforma.docx.pdf.*
import dev.nuaforma.pdf.PdfProcessValidator
import dev.nuaforma.server.adapters.ktor.KtorServer
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

class NativePdfRestApiTest {
    @TempDir lateinit var temporary: Path

    @Test fun `actual native provider preserves embedded and REST output semantics`() = testApplication {
        val root = Path.of(System.getProperty("nuaforma.repositoryRoot"))
        val fonts = listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic", "boldItalic" to "BoldItalic").map { (face, file) ->
            NativePdfFont("Liberation Sans", face, Files.readAllBytes(root.resolve("nuaforma-pdf/src/test/resources/fonts/LiberationSans-$file.ttf")))
        }
        val validator = PdfProcessValidator(Path.of(System.getProperty("nuaforma.pdf.validation.java")),
            Files.list(Path.of(System.getProperty("nuaforma.pdf.validation"))).use { paths -> paths.filter { it.toString().endsWith(".jar") }.sorted().toList() }, temporary)
        val provider = DocxPdfProvider(NativePdfConfiguration(fonts, validator))
        val registry = ProviderRegistry.registered(listOf(ProviderRegistration(ProviderMetadata("nuaforma-docx-pdf",
            DocxPdfProvider::class.java.name, "0.2.0-alpha.2", ContractVersion.CURRENT)) { provider }))
        application { KtorServer(registry, ServerConfiguration(temporaryDirectory = temporary)).installRoutes(this) }
        val source = Files.readAllBytes(root.resolve("nuaforma-docx-pdf/src/test/resources/native-profile/word-letter.docx"))
        val expected = ByteArrayOutputStream()
        provider.generate(GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
            FormatRequest(DocumentFormat.PDF, mapOf("profile" to "standard")))), expected, GenerationContext())
        val boundary = "NATIVE-PDF-BOUNDARY"
        fun multipart(request: String): ByteArray {
            val body = ByteArrayOutputStream()
            fun part(name: String, type: String, bytes: ByteArray) {
                val filename = if (name == "template") "; filename=\"source.docx\"" else ""
                body.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"$filename\r\nContent-Type: $type\r\n\r\n".toByteArray())
                body.write(bytes); body.write("\r\n".toByteArray())
            }
            part("request", "application/json", request.toByteArray())
            part("template", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", source)
            body.write("--$boundary--\r\n".toByteArray())
            return body.toByteArray()
        }
        val body = multipart("""{"providerId":"nuaforma-docx-pdf","input":{"format":"docx"},"output":{"format":"pdf","options":{"profile":"standard"}}}""")
        val response = client.post("/api/v1/documents") {
            contentType(ContentType.MultiPart.FormData.withParameter("boundary", boundary)); setBody(body)
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().startsWith("application/pdf"))
        Loader.loadPDF(expected.toByteArray()).use { embedded -> Loader.loadPDF(response.bodyAsBytes()).use { rest ->
            assertEquals(embedded.numberOfPages, rest.numberOfPages)
            assertEquals(PDFTextStripper().getText(embedded), PDFTextStripper().getText(rest))
            val expectedBox = embedded.getPage(0).mediaBox
            val actualBox = rest.getPage(0).mediaBox
            assertEquals(expectedBox.lowerLeftX, actualBox.lowerLeftX)
            assertEquals(expectedBox.lowerLeftY, actualBox.lowerLeftY)
            assertEquals(expectedBox.upperRightX, actualBox.upperRightX)
            assertEquals(expectedBox.upperRightY, actualBox.upperRightY)
            assertTrue(rest.getPage(0).resources.fontNames.all { rest.getPage(0).resources.getFont(it).isEmbedded })
        } }
        for (profile in listOf("standard", "pdfa-2b", "pdfa-3b", "pdfa-1b")) {
            val request = """{"input":{"format":"docx"},"output":{"format":"pdf","options":{"profile":"$profile"}},"data":{"reference":"NF-057"},"options":{"watermark":{"text":"CONCEPT {reference}","opacity":0.25}}}"""
            val marked = client.post("/api/v1/documents") {
                contentType(ContentType.MultiPart.FormData.withParameter("boundary", boundary)); setBody(multipart(request))
            }
            if (profile == "pdfa-1b") {
                assertEquals(HttpStatusCode.UnprocessableEntity, marked.status, marked.bodyAsText())
                assertTrue(marked.bodyAsText().contains("UNSUPPORTED_OPTION"))
            } else {
                assertEquals(HttpStatusCode.OK, marked.status, marked.bodyAsText())
                Loader.loadPDF(marked.bodyAsBytes()).use { pdf ->
                    val logical = org.apache.pdfbox.pdfparser.PDFStreamParser(pdf.getPage(0)).parse()
                        .filterIsInstance<org.apache.pdfbox.cos.COSDictionary>().mapNotNull { it.getString(org.apache.pdfbox.cos.COSName.ACTUAL_TEXT) }
                    assertEquals(listOf("CONCEPT NF-057"), logical)
                }
            }
        }
        assertTrue(client.get("/api/v1/capabilities").bodyAsText().contains("pdfa-3b"))
        assertEquals(0, Files.list(temporary).use { it.count() })
    }
}
