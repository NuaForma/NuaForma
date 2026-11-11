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

class NativeEditingBookmarkTest {
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
    fun `empty unused editing bookmarks preserve original packages Word pages and existing profile goldens`() {
        val provider = provider()
        val output = Files.createDirectories(Path.of("build/256-editing-bookmark"))
        for ((name, baseline) in listOf("a4" to "trailing-1", "letter" to "bands-letter")) {
            val base = "/editing-bookmark/$name/"
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
    fun `malformed or referenced empty editing bookmarks cannot silently disappear`() {
        val provider = provider()
        val parts = PdfFixture.unzip(resource("/editing-bookmark/a4/source.docx"))
        val before = parts.getValue("word/document.xml").decodeToString()
        val start = "<w:bookmarkStart w:id=\"701\" w:name=\"_GoBack\"/>"
        val end = "<w:bookmarkEnd w:id=\"701\"/>"
        val after = listOf(before.replace(start, start.replace("/>", " extra=\"1\"/>")),
            before.replace(end, ""), before.replace(end, "<w:bookmarkEnd w:id=\"702\"/>"),
            before.replace("<w:sectPr", "<w:p><w:fldSimple w:instr=\"REF _GoBack\"><w:r><w:t>Cached text</w:t></w:r></w:fldSimple></w:p><w:sectPr"),
            before.replace("<w:sectPr", "<w:p><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>" +
                "<w:r><w:instrText> PAGEREF _Go</w:instrText></w:r><w:r><w:instrText>Back </w:instrText></w:r>" +
                "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:t>1</w:t></w:r>" +
                "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p><w:sectPr"),
            before.replace("<w:sectPr", "<w:p><w:hyperlink w:anchor=\"_GoBack\"><w:r><w:t>Go back</w:t></w:r></w:hyperlink></w:p><w:sectPr"))
        for (changed in after) {
            assertNotEquals(before, changed)
            val source = PdfFixture.zip(parts.mapValues { (part, bytes) -> if (part == "word/document.xml") changed.toByteArray() else bytes })
            val output = ByteArrayOutputStream()
            val error = assertThrows(GenerationException::class.java) {
                provider.generate(request(source), output, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
            }
            assertEquals(0, output.size())
            assertTrue(error.diagnostics.any { it.code == DiagnosticCodes.UNSUPPORTED_INPUT && it.part == "word/document.xml" && it.sourceContext?.path != null })
            if (changed.contains("REF _GoBack") || changed.contains("PAGEREF _Go") || changed.contains("w:anchor=\"_GoBack\"")) {
                assertTrue(error.diagnostics.any { it.sourceContext?.explanation == "Bookmark requires following visible paragraph text" })
            }
        }
    }
}
