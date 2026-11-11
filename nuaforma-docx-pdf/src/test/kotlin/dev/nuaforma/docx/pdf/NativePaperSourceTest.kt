package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import dev.nuaforma.pdf.PdfProcessValidator
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO

class NativePaperSourceTest {
    @TempDir lateinit var temporary: Path

    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes() }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun provider() = DocxPdfProvider(NativePdfConfiguration(listOf(NativePdfFont("Liberation Sans", "regular",
        resource("/own-engine-fonts/LiberationSans-Regular.ttf"))), PdfProcessValidator(
        Path.of(System.getProperty("nuaforma.pdf.validation.java")),
        Files.list(Path.of(System.getProperty("nuaforma.pdf.validation"))).use { paths -> paths.filter { it.toString().endsWith(".jar") }.sorted().toList() }, temporary)))

    private fun request(source: ByteArray, profile: String = "standard") = GenerationRequest(source.inputStream(),
        GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile))))

    @Test
    fun `printer tray preferences preserve original packages Word pages and existing profile goldens`() {
        val provider = provider()
        val output = Files.createDirectories(Path.of("build/255-paper-source"))
        for ((name, baseline) in listOf("a4" to "trailing-1", "letter" to "bands-letter")) {
            val base = "/printer-tray-selection/$name/"
            val source = resource(base + "source.docx")
            val reference = resource(base + "word-reference.pdf")
            val receipt = resource(base + "word-reference.pdf.provenance.json").decodeToString()
            val goldenReceipt = resource(base + "golden-provenance.json").decodeToString()
            assertTrue(receipt.contains(hash(source)) && receipt.contains(hash(reference)))
            assertTrue(goldenReceipt.contains(hash(source)) && goldenReceipt.contains(hash(reference)))
            val legacy = resource("/passive-office-package/$name/source.docx")
            val oldParts = PdfFixture.unzip(legacy)
            val newParts = PdfFixture.unzip(source)
            assertEquals(oldParts.keys, newParts.keys)
            oldParts.filterKeys { it != "word/document.xml" }.forEach { (part, bytes) -> assertArrayEquals(bytes, newParts[part], part) }
            assertTrue(goldenReceipt.contains(hash(legacy)))
            val cleaned = ByteArrayOutputStream()
            assertTrue(provider.cleanTemplate(source.inputStream(), cleaned,
                GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))).changes.isEmpty())
            assertArrayEquals(source, cleaned.toByteArray())
            Loader.loadPDF(reference).use { word ->
                Loader.loadPDF(resource("/passive-office-package/$name/word-reference.pdf")).use { oldWord ->
                    assertEquals(1, word.numberOfPages)
                    assertImagesMatch(PDFRenderer(oldWord).renderImageWithDPI(0, 96f), PDFRenderer(word).renderImageWithDPI(0, 96f)) {
                        pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                    }
                }
                for (profile in listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b")) {
                    val rendered = ByteArrayOutputStream()
                    provider.generate(request(source, profile), rendered, GenerationContext())
                    Files.write(output.resolve("$name-$profile.pdf"), rendered.toByteArray())
                    Loader.loadPDF(rendered.toByteArray()).use { actual ->
                        assertEquals(1, actual.numberOfPages)
                        assertEquals(PDFTextStripper().getText(word).replace(Regex("\\s+"), " ").trim(),
                            PDFTextStripper().getText(actual).replace(Regex("\\s+"), " ").trim())
                        assertEquals(word.getPage(0).mediaBox.width, actual.getPage(0).mediaBox.width, .1f)
                        assertEquals(word.getPage(0).mediaBox.height, actual.getPage(0).mediaBox.height, .1f)
                        assertTrue(actual.getPage(0).resources.fontNames.all { actual.getPage(0).resources.getFont(it).isEmbedded })
                        val golden = resource("/typography-preferences/$baseline/page-1.png")
                        assertTrue(goldenReceipt.contains(hash(golden)))
                        assertImagesMatch(ImageIO.read(golden.inputStream()), PDFRenderer(actual).renderImageWithDPI(0, 96f)) {
                            pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `invalid original tray settings retain focused diagnostics and cannot publish PDF`() {
        val provider = provider()
        val parts = PdfFixture.unzip(resource("/printer-tray-selection/a4/source.docx"))
        val before = parts.getValue("word/document.xml").decodeToString()
        val original = "<w:paperSrc w:first=\"15\" w:other=\"15\"/>"
        for (replacement in listOf("<w:paperSrc w:first=\"65536\"/>", "<w:paperSrc w:other=\"-1\"/>",
            "<w:paperSrc extra=\"1\"/>", "<w:paperSrc/><w:paperSrc/>", "<w:paperSrc>text</w:paperSrc>")) {
            val after = before.replace(original, replacement)
            assertNotEquals(before, after)
            val source = PdfFixture.zip(parts.mapValues { (part, bytes) -> if (part == "word/document.xml") after.toByteArray() else bytes })
            val output = ByteArrayOutputStream()
            val error = assertThrows(GenerationException::class.java) {
                provider.generate(request(source), output, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
            }
            assertEquals(0, output.size())
            assertTrue(error.diagnostics.any { it.code == DiagnosticCodes.UNSUPPORTED_INPUT && it.part == "word/document.xml" && it.sourceContext?.path != null })
        }
    }
}
