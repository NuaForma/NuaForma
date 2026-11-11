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

class NativeTableBookmarkEndsTest {
    @TempDir lateinit var temporary: Path

    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes() }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun provider() = DocxPdfProvider(NativePdfConfiguration(listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic", "boldItalic" to "BoldItalic")
        .map { (face, file) -> NativePdfFont("Liberation Sans", face, resource("/own-engine-fonts/LiberationSans-$file.ttf")) }, PdfProcessValidator(
            Path.of(System.getProperty("nuaforma.pdf.validation.java")),
            Files.list(Path.of(System.getProperty("nuaforma.pdf.validation"))).use { paths -> paths.filter { it.toString().endsWith(".jar") }.sorted().toList() }, temporary)))

    private fun request(source: ByteArray, profile: String = "standard") = GenerationRequest(source.inputStream(),
        GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile))))

    private fun lines(pdf: org.apache.pdfbox.pdmodel.PDDocument) = PDFTextStripper().apply { sortByPosition = true }.getText(pdf)
        .lineSequence().map { it.replace(Regex("[\\p{Z}\\t]+"), " ").trim() }.filter { it.isNotBlank() }.toList()

    @Test
    fun `ordered table bookmark endings preserve Word layout source bytes and reviewed profile goldens`() {
        val provider = provider()
        val output = Files.createDirectories(Path.of("build/253-table-bookmark-ends"))
        for (name in listOf("a4", "letter")) {
            val base = "/table-bookmark-ends/$name/"
            val source = resource(base + "source.docx")
            val control = resource(base + "control-source.docx")
            val reference = resource(base + "word-reference.pdf")
            val controlReference = resource(base + "control-word-reference.pdf")
            val wordReceipt = resource(base + "word-reference.pdf.provenance.json").decodeToString()
            val controlReceipt = resource(base + "control-word-reference.pdf.provenance.json").decodeToString()
            val goldenReceipt = resource(base + "golden-provenance.json").decodeToString()
            assertTrue(wordReceipt.contains(hash(source)) && wordReceipt.contains(hash(reference)))
            assertTrue(controlReceipt.contains(hash(control)) && controlReceipt.contains(hash(controlReference)))
            assertTrue(listOf(source, control, reference, controlReference).all { goldenReceipt.contains(hash(it)) })
            val before = PdfFixture.unzip(control)
            val after = PdfFixture.unzip(source)
            assertEquals(before.keys, after.keys)
            before.filterKeys { it != "word/document.xml" }.forEach { (part, bytes) -> assertArrayEquals(bytes, after[part], part) }
            for (input in listOf(source, control)) {
                val cleaned = ByteArrayOutputStream()
                assertTrue(provider.cleanTemplate(input.inputStream(), cleaned,
                    GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))).changes.isEmpty())
                assertArrayEquals(input, cleaned.toByteArray())
            }
            Loader.loadPDF(reference).use { word ->
                Loader.loadPDF(controlReference).use { oldWord ->
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
                        assertEquals(lines(word), lines(actual))
                        assertEquals(word.getPage(0).mediaBox.width, actual.getPage(0).mediaBox.width, .1f)
                        assertEquals(word.getPage(0).mediaBox.height, actual.getPage(0).mediaBox.height, .1f)
                        assertTrue(actual.getPage(0).resources.fontNames.all { actual.getPage(0).resources.getFont(it).isEmbedded })
                        val golden = resource(base + "page-1.png")
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
    fun `invalid marker order and affected text fields cannot publish partial PDF`() {
        val provider = provider()
        val parts = PdfFixture.unzip(resource("/table-bookmark-ends/a4/source.docx"))
        val before = parts.getValue("word/document.xml").decodeToString()
        val reference = """<w:p><w:fldSimple w:instr="REF _FirstRow"><w:r><w:t>Stale cache</w:t></w:r></w:fldSimple></w:p>"""
        val invalid = listOf(before.replace("<w:bookmarkEnd w:id=\"21\"/>", "<w:bookmarkEnd w:id=\"TEMP\"/>")
            .replace("<w:bookmarkEnd w:id=\"22\"/>", "<w:bookmarkEnd w:id=\"21\"/>")
            .replace("<w:bookmarkEnd w:id=\"TEMP\"/>", "<w:bookmarkEnd w:id=\"22\"/>"),
            before.replace("<w:bookmarkEnd w:id=\"21\"/>", "<w:bookmarkEnd w:id=\"21\" extra=\"1\"/>"),
            before.replace("<w:bookmarkEnd w:id=\"21\"/>", "<w:bookmarkEnd w:id=\"21\">text</w:bookmarkEnd>"),
            before.replace("<w:sectPr", reference + "<w:sectPr"))
        for (after in invalid) {
            assertNotEquals(before, after)
            val source = PdfFixture.zip(parts.mapValues { (name, bytes) -> if (name == "word/document.xml") after.toByteArray() else bytes })
            val output = ByteArrayOutputStream()
            val error = assertThrows(GenerationException::class.java) {
                provider.generate(request(source), output, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
            }
            assertEquals(0, output.size())
            assertTrue(error.diagnostics.any { it.code == DiagnosticCodes.UNSUPPORTED_INPUT && it.part == "word/document.xml" && it.sourceContext?.path != null })
        }
    }
}
