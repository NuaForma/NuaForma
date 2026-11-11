package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import dev.nuaforma.pdf.PdfProcessValidator
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

class NativeMinimumRowHeightTest {
    @TempDir lateinit var temporary: Path

    private fun bytes(path: String) = requireNotNull(javaClass.getResourceAsStream(path)).use { it.readBytes() }

    private fun hash(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }

    private fun text(document: PDDocument) = PDFTextStripper().getText(document).replace(Regex("[\\p{Z}\\s]+"), " ").trim()

    private fun configured(): DocxPdfProvider {
        val fonts = listOf("Regular" to "regular", "Bold" to "bold", "Italic" to "italic", "BoldItalic" to "boldItalic").map { (file, face) ->
            NativePdfFont("Liberation Sans", face, bytes("/own-engine-fonts/LiberationSans-$file.ttf"))
        }
        val directory = Path.of(System.getProperty("nuaforma.pdf.validation"))
        val classpath = Files.list(directory).use { paths -> paths.filter { it.toString().endsWith(".jar") }.sorted().toList() }
        val validator = PdfProcessValidator(Path.of(System.getProperty("nuaforma.pdf.validation.java")), classpath, temporary)
        return DocxPdfProvider(NativePdfConfiguration(fonts, validator))
    }

    private fun generate(provider: DocxPdfProvider, source: ByteArray, profile: String,
                         output: ByteArrayOutputStream = ByteArrayOutputStream()): ByteArray {
        provider.generate(GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
            FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile)))), output,
            GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
        return output.toByteArray()
    }

    private data class Position(val x: Float, val y: Float)

    private fun positions(document: PDDocument): Map<String, Position> {
        val found = linkedMapOf<String, Position>()
        val stripper = object : PDFTextStripper() {

            override fun writeString(text: String, positions: List<TextPosition>) {
                val raw = StringBuilder()
                val characters = mutableListOf<TextPosition>()
                for (position in positions) {
                    raw.append(position.unicode)
                    repeat(position.unicode.length) { characters += position }
                }
                for (match in Regex("[RFH][0-9]{2}[AB]").findAll(raw)) {
                    val position = characters[match.range.first]
                    assertNull(found.put("$currentPageNo:${match.value}", Position(position.xDirAdj, position.yDirAdj)))
                }
            }

        }
        stripper.getText(document)
        return found
    }

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `minimum rows preserve Word positions page breaks headers and all validated profiles`(paper: String) {
        val folder = "/minimum-row-heights/$paper/"
        val proof = bytes(folder + "golden-provenance.json").decodeToString()
        val source = bytes(folder + "source.docx")
        val implicit = bytes(folder + "implicit-source.docx")
        val reference = bytes(folder + "word-reference.pdf")
        val implicitReference = bytes(folder + "implicit-word-reference.pdf")
        val goldens = (1..3).map { bytes(folder + "page-$it.png") }
        for (value in listOf(source, implicit, reference, implicitReference) + goldens) assertTrue(proof.contains(hash(value)))
        assertTrue(bytes(folder + "word-reference.pdf.provenance.json").decodeToString().contains(hash(source)))
        assertTrue(bytes(folder + "implicit-word-reference.pdf.provenance.json").decodeToString().contains(hash(implicit)))
        val provider = configured()
        for (value in listOf(source, implicit)) {
            val cleaned = ByteArrayOutputStream()
            assertTrue(provider.cleanTemplate(value.inputStream(), cleaned,
                GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))).changes.isEmpty())
            assertArrayEquals(value, cleaned.toByteArray())
        }
        Loader.loadPDF(reference).use { word ->
            assertEquals(3, word.numberOfPages)
            val expected = positions(word)
            assertEquals(36, expected.size)
            Loader.loadPDF(implicitReference).use { implicitWord ->
                assertEquals(3, implicitWord.numberOfPages)
                for (page in 0..2) assertImagesMatch(PDFRenderer(word).renderImageWithDPI(page, 96f),
                    PDFRenderer(implicitWord).renderImageWithDPI(page, 96f)) {
                    pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                }
            }
            for ((variant, input) in listOf("explicit" to source, "implicit" to implicit)) {
                val profiles = if (variant == "explicit") listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard")
                for (profile in profiles) Loader.loadPDF(generate(provider, input, profile)).use { actual ->
                    assertEquals(3, actual.numberOfPages)
                    assertEquals(text(word), text(actual))
                    val found = positions(actual)
                    assertEquals(expected.keys, found.keys)
                    for ((marker, before) in expected) {
                        val after = found.getValue(marker)
                        assertEquals(before.x, after.x, .1f, marker)
                        assertEquals(before.y, after.y, .21f, marker)
                    }
                    for (page in 0..2) {
                        assertEquals(word.getPage(page).mediaBox.width, actual.getPage(page).mediaBox.width, .1f)
                        assertEquals(word.getPage(page).mediaBox.height, actual.getPage(page).mediaBox.height, .1f)
                        assertTrue(actual.getPage(page).resources.fontNames.all { actual.getPage(page).resources.getFont(it).isEmbedded })
                        assertImagesMatch(ImageIO.read(goldens[page].inputStream()), PDFRenderer(actual).renderImageWithDPI(page, 96f)) {
                            pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                        }
                    }
                }
            }
        }
    }

    private fun changed(source: ByteArray, replacement: String): ByteArray = ByteArrayOutputStream().also { result ->
        ZipOutputStream(result).use { output -> ZipInputStream(source.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val original = input.readBytes()
                val changed = if (entry.name == "word/document.xml") {
                    val before = original.decodeToString()
                    val after = Regex("<w:trHeight[^>]*/>").replaceFirst(before, replacement)
                    assertNotEquals(before, after)
                    after.toByteArray()
                } else original
                output.putNextEntry(ZipEntry(entry.name))
                output.write(changed)
                output.closeEntry()
            }
        } }
    }.toByteArray()

    @Test
    fun `malformed or exact row heights retain source diagnostics and publish no PDF`() {
        val provider = configured()
        val source = bytes("/minimum-row-heights/a4/source.docx")
        for (replacement in listOf("<w:trHeight/>", """<w:trHeight w:val="-1"/>""",
            """<w:trHeight w:val="288001"/>""", """<w:trHeight w:val="1.5"/>""",
            """<w:trHeight w:val="1" w:hRule="bad"/>""", """<w:trHeight val="1"/>""",
            """<w:trHeight w:val="1" extra="1"/>""", """<w:trHeight w:val="1"/><w:trHeight w:val="2"/>""",
            """<w:trHeight w:val="1" w:hRule="auto"/>""", """<w:trHeight w:val="800" w:hRule="exact"/>""")) {
            val output = ByteArrayOutputStream()
            val failure = assertThrows(GenerationException::class.java) { generate(provider, changed(source, replacement), "standard", output) }
            assertEquals(0, output.size())
            val diagnostic = failure.diagnostics.single()
            assertEquals("word/document.xml", diagnostic.part)
            assertTrue(diagnostic.sourceContext?.path.orEmpty().contains("trHeight"), diagnostic.toString())
        }
    }

}
