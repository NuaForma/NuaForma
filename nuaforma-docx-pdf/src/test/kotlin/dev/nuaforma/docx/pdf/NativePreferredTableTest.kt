package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.pdf.PdfProcessValidator
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO

class NativePreferredTableTest {
    @TempDir lateinit var temporary: Path

    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes() }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun markers(pdf: org.apache.pdfbox.pdmodel.PDDocument): Map<String, TextPosition> {
        val characters = mutableListOf<TextPosition>()
        val contents = StringBuilder()
        val reader = object : PDFTextStripper() {

            override fun processTextPosition(text: TextPosition) {
                repeat(text.unicode.length) { characters += text }
                contents.append(text.unicode)
                super.processTextPosition(text)
            }

        }
        reader.getText(pdf)
        return Regex("(?:PHYSICAL|PERCENT|IMPLICIT|MERGED)_[1-3]_[12]|MERGED_SHARED_COLUMNS|END_PREFERRED_TABLES")
            .findAll(contents).associate { it.value to characters[it.range.first] }
    }

    private fun provider() = DocxPdfProvider(NativePdfConfiguration(listOf("Regular" to "regular", "Bold" to "bold", "Italic" to "italic", "BoldItalic" to "boldItalic").map { (file, face) ->
        NativePdfFont("Liberation Sans", face, resource("/own-engine-fonts/LiberationSans-$file.ttf"))
    }, PdfProcessValidator(Path.of(System.getProperty("nuaforma.pdf.validation.java")),
        Files.list(Path.of(System.getProperty("nuaforma.pdf.validation"))).use { paths -> paths.filter { it.toString().endsWith(".jar") }.sorted().toList() }, temporary)))

    @Test
    fun `preferred tables retain Word contents on both papers through the real provider and all PDF profiles`() {
        val provider = provider()
        val output = Files.createDirectories(Path.of("build/259-preferred-tables"))
        for (paper in listOf("a4", "letter")) {
            val source = resource("/preferred-table-widths/$paper/source.docx")
            val original = source.copyOf()
            val reference = resource("/preferred-table-widths/$paper/word-reference.pdf")
            val proof = resource("/preferred-table-widths/$paper/word-reference.pdf.provenance.json").decodeToString()
            val goldenProof = resource("/preferred-table-widths/$paper/golden-provenance.json").decodeToString()
            assertTrue(proof.contains(hash(source)) && proof.contains(hash(reference)))
            assertTrue(goldenProof.contains(hash(source)) && goldenProof.contains(hash(reference)))
            val clean = ByteArrayOutputStream()
            assertTrue(provider.cleanTemplate(source.inputStream(), clean, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))).changes.isEmpty())
            assertArrayEquals(source, clean.toByteArray())
            Loader.loadPDF(reference).use { word ->
                val expected = PDFTextStripper().getText(word).replace(Regex("\\s+"), " ").trim()
                val expectedMarkers = markers(word)
                assertEquals(26, expectedMarkers.size)
                for (profile in listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b")) {
                    val request = GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                        FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile))))
                    val bytes = ByteArrayOutputStream()
                    provider.generate(request, bytes, GenerationContext())
                    Files.write(output.resolve("$paper-$profile.pdf"), bytes.toByteArray())
                    Loader.loadPDF(bytes.toByteArray()).use { actual ->
                        assertEquals(word.numberOfPages, actual.numberOfPages)
                        assertEquals(expected, PDFTextStripper().getText(actual).replace(Regex("\\s+"), " ").trim())
                        val actualMarkers = markers(actual)
                        assertEquals(expectedMarkers.keys, actualMarkers.keys)
                        for ((label, position) in expectedMarkers) {
                            val measured = actualMarkers.getValue(label)
                            assertEquals(position.xDirAdj, measured.xDirAdj, .1f, label)
                            assertEquals(position.yDirAdj, measured.yDirAdj, .2f, label)
                        }
                        val golden = resource("/preferred-table-widths/$paper/page-1.png")
                        assertTrue(goldenProof.contains(hash(golden)))
                        assertImagesMatch(ImageIO.read(golden.inputStream()), PDFRenderer(actual).renderImageWithDPI(0, 96f)) {
                            pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                        }
                        for (page in actual.pages) {
                            assertTrue(page.resources.fontNames.all { page.resources.getFont(it).isEmbedded })
                        }
                        val bounds = object : PDFTextStripper() {

                            override fun processTextPosition(text: TextPosition) {
                                assertTrue(text.xDirAdj >= 0f && text.xDirAdj + text.widthDirAdj <= actual.getPage(currentPageNo - 1).mediaBox.width + .1f)
                                super.processTextPosition(text)
                            }

                        }
                        bounds.getText(actual)
                    }
                }
            }
            assertArrayEquals(original, source)
        }
    }

    @Test
    fun `invalid original width attributes and duplicates retain source diagnostics without output`() {
        val provider = provider()
        val parts = PdfFixture.unzip(resource("/preferred-table-widths/a4/source.docx"))
        val before = parts.getValue("word/document.xml").decodeToString()
        val original = "<w:tblW w:w=\"6400\" w:type=\"dxa\"/>"
        for (replacement in listOf(original.replace("/>", " extra=\"1\"/>"), original + original,
            "<w:tblW w:w=\"5001\" w:type=\"pct\"/>", "<w:tblW w:w=\"6400\" w:type=\"unknown\"/>")) {
            val after = before.replace(original, replacement)
            assertNotEquals(before, after)
            val source = PdfFixture.zip(parts.mapValues { (part, bytes) -> if (part == "word/document.xml") after.toByteArray() else bytes })
            val output = ByteArrayOutputStream()
            val error = assertThrows(GenerationException::class.java) {
                provider.generate(GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                    FormatRequest(DocumentFormat.PDF, mapOf("profile" to "standard")))), output,
                    GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
            }
            assertEquals(0, output.size())
            assertTrue(error.diagnostics.any { it.code == DiagnosticCodes.UNSUPPORTED_INPUT && it.part == "word/document.xml" && it.sourceContext?.path != null })
        }
    }

}
