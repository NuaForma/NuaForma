package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.pdf.PdfProcessValidator
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.*
import javax.imageio.ImageIO

/** Reproducible source builder for the initial, reviewed diagnostic PDF golden images. */
class TemplateDiagnosticPdfTest {
    @TempDir lateinit var temporary: Path
    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes() }
    private fun provider(): DocxPdfProvider {
        val fonts = listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic", "boldItalic" to "BoldItalic").map { (face, file) ->
            NativePdfFont("Liberation Sans", face, resource("/own-engine-fonts/LiberationSans-$file.ttf"))
        }
        val validator = PdfProcessValidator(Path.of(System.getProperty("nuaforma.pdf.validation.java")),
            Files.list(Path.of(System.getProperty("nuaforma.pdf.validation"))).use { paths -> paths.filter { it.toString().endsWith(".jar") }.sorted().toList() }, temporary)
        return DocxPdfProvider(NativePdfConfiguration(fonts, validator))
    }
    private fun template(marker: String, letter: Boolean = false): ByteArray = ByteArrayOutputStream().also { target ->
        ZipOutputStream(target).use { output -> ZipInputStream(resource("/native-profile/word-letter.docx").inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val bytes = input.readBytes()
                output.putNextEntry(ZipEntry(entry.name))
                val xml = if (entry.name == "word/document.xml") bytes.decodeToString().replace("<w:body>",
                    "<w:body><w:p><w:r><w:rPr><w:rFonts w:ascii=\"Liberation Sans\" w:hAnsi=\"Liberation Sans\"/></w:rPr><w:t>$marker</w:t></w:r></w:p>") else null
                output.write(if (xml == null) bytes else (if (letter) xml.replace(Regex("<w:pgSz[^>]*/>"), "<w:pgSz w:w=\"12240\" w:h=\"15840\"/>") else xml).toByteArray())
                output.closeEntry()
            }
        } }
    }.toByteArray()
    private fun request(marker: String) = GenerationRequest(template(marker).inputStream(),
        GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.PDF, mapOf("profile" to "pdfa-1b"))),
        mapOf("name" to "PRIVATE_DATA_MUST_NOT_BE_INSERTED"))

    @Test fun `validated literal diagnostic PDF marks original content and matches reviewed page images`() {
        val output = ByteArrayOutputStream()
        val result = provider().diagnoseTemplate(request("Prefix {broken"), output, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
        assertEquals("standard", result.output.options["profile"])
        val directory = Files.createDirectories(Path.of("build/150-diagnostic-golden"))
        Files.write(directory.resolve("diagnostic.pdf"), output.toByteArray())
        Loader.loadPDF(output.toByteArray()).use { pdf ->
            val text = PDFTextStripper().getText(pdf)
            assertTrue(text.contains("Prefix [ERROR 1] {broken"), text)
            assertTrue(text.contains("Generation failed."), text)
            assertTrue(text.contains("UNCLOSED_TEMPLATE_MARKER"), text)
            assertFalse(text.contains("PRIVATE_DATA_MUST_NOT_BE_INSERTED"))
            assertEquals(2, pdf.numberOfPages)
            val renderer = PDFRenderer(pdf)
            val actual = (0 until pdf.numberOfPages).map { page ->
                assertTrue(pdf.getPage(page).resources.fontNames.all { pdf.getPage(page).resources.getFont(it).isEmbedded })
                renderer.renderImageWithDPI(page, 96f).also { ImageIO.write(it, "png", directory.resolve("page-${page + 1}.png").toFile()) }
            }
            actual.forEachIndexed { page, image ->
                val expected = javaClass.getResourceAsStream("/template-diagnostics/page-${page + 1}.png")
                assertNotNull(expected, "Review the initial build/150-diagnostic-golden candidate; tests never create or overwrite golden resources")
                expected!!.use {
                    assertImagesMatch(ImageIO.read(it), image, "Diagnostic page ${page + 1}") {
                        pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                    }
                }
            }
        }
    }

    @Test fun `production rejects diagnostics and cancellation publishes no bytes`() {
        val provider = provider()
        for (context in listOf(GenerationContext(), GenerationContext(GenerationLimits(), CancellationToken { true }, null,
            java.time.Clock.systemUTC(), GenerationLogging(LoggingMode.DEVELOPMENT)))) {
            val output = ByteArrayOutputStream()
            assertThrows(GenerationException::class.java) { provider.diagnoseTemplate(request("{broken"), output, context) }
            assertEquals(0, output.size())
        }
        val output = ByteArrayOutputStream()
        val unexpected = GenerationRequest(object : java.io.InputStream() { override fun read(): Int = error("Must not read template") },
            GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.PDF, mapOf("unknown" to true))))
        val rejected = assertThrows(GenerationException::class.java) {
            provider.diagnoseTemplate(unexpected, output, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
        }
        assertEquals(RejectionReason.UNRECOGNIZED_PDF_OPTIONS, rejected.diagnostics.first().rejectionReason)
        assertEquals(0, output.size())
    }

    @Test fun `diagnostic pages respect US Letter and preserve literal markers`() {
        val output = ByteArrayOutputStream()
        provider().diagnoseTemplate(GenerationRequest(template("Prefix {trim(name,)}", true).inputStream(),
            GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.PDF))), output,
            GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
        Loader.loadPDF(output.toByteArray()).use { pdf ->
            for (page in pdf.pages) {
                assertEquals(612f, page.mediaBox.width, 0.1f)
                assertEquals(792f, page.mediaBox.height, 0.1f)
            }
            val text = PDFTextStripper().getText(pdf)
            assertTrue(text.contains("UNEXPECTED_EXPRESSION_TOKEN"))
            assertTrue(text.contains("{trim(name,"))
        }
    }
    @Test fun `hidden invalid markers and unavailable fonts are omitted before validated PDF rendering`() {
        fun withHidden(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { target ->
            ZipOutputStream(target).use { zip -> ZipInputStream(bytes.inputStream()).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val original = input.readBytes()
                    zip.putNextEntry(ZipEntry(entry.name))
                    val changed = if (entry.name == "word/document.xml") original.decodeToString()
                        .replaceFirst("<w:rPr>", "<w:rPr><w:vanish w:val=\"false\"/><w:webHidden/>")
                        .replaceFirst("</w:t></w:r></w:p>",
                        "</w:t></w:r><w:r><w:rPr><w:vanish/><w:rFonts w:ascii=\"Missing Hidden Font\" w:hAnsi=\"Missing Hidden Font\"/></w:rPr><w:t>{HIDDEN_BAD(</w:t></w:r></w:p>").toByteArray() else original
                    zip.write(changed); zip.closeEntry()
                }
            } }
        }.toByteArray()
        val requirements = GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.PDF))
        fun generate(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also {
            provider().generate(GenerationRequest(bytes.inputStream(), requirements, mapOf("name" to "Ada")), it, GenerationContext())
        }.toByteArray()
        val ordinary = template("Visible {name}")
        val expected = generate(ordinary)
        val actual = generate(withHidden(ordinary))
        val directory = Files.createDirectories(Path.of("build/154-hidden-text"))
        Files.write(directory.resolve("visible.pdf"), actual)
        Loader.loadPDF(expected).use { baseline -> Loader.loadPDF(actual).use { pdf ->
            val text = PDFTextStripper().getText(pdf)
            assertTrue(text.contains("Visible Ada"), text)
            assertFalse(text.contains("HIDDEN_BAD"), text)
            assertEquals(baseline.numberOfPages, pdf.numberOfPages)
            val left = PDFRenderer(baseline); val right = PDFRenderer(pdf)
            for (page in 0 until pdf.numberOfPages) {
                val image = right.renderImageWithDPI(page, 96f)
                ImageIO.write(image, "png", directory.resolve("page-${page + 1}.png").toFile())
                assertImagesMatch(left.renderImageWithDPI(page, 96f), image, "Hidden text page ${page + 1}") {
                    pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                }
            }
        } }
        val diagnostic = ByteArrayOutputStream()
        provider().diagnoseTemplate(GenerationRequest(withHidden(template("Visible {broken")).inputStream(), requirements), diagnostic,
            GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
        Loader.loadPDF(diagnostic.toByteArray()).use { pdf ->
            val text = PDFTextStripper().getText(pdf)
            assertTrue(text.contains("[ERROR 1] {broken"), text)
            assertFalse(text.contains("HIDDEN_BAD"), text)
        }
    }

}
