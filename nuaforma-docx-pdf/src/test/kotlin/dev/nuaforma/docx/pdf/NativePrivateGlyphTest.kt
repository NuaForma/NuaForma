package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import dev.nuaforma.pdf.PdfFontEmbedding
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
import org.junit.jupiter.params.provider.EnumSource
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO

class NativePrivateGlyphTest {
    @TempDir lateinit var temporary: Path
    private val equivalents = mapOf('\u2709' to '\uF003', '\u2665' to '\uF004', '\u2713' to '\uF00C', '\u00D7' to '\uF00D')

    private fun bytes(path: String) = requireNotNull(javaClass.getResourceAsStream(path)).use { it.readBytes() }

    private fun hash(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }

    private fun configured(embedding: PdfFontEmbedding, symbols: Boolean = true): DocxPdfProvider {
        val fonts = listOf("Regular" to "regular", "Bold" to "bold", "Italic" to "italic", "BoldItalic" to "boldItalic").map { (file, face) ->
            NativePdfFont("Liberation Sans", face, bytes("/own-engine-fonts/LiberationSans-$file.ttf"))
        } + if (symbols) listOf(NativePdfFont("Font Awesome 6 Free Solid", "regular", bytes("/own-engine-fonts/FontAwesomeFree-Solid.ttf"), embedding)) else emptyList()
        val validation = Path.of(System.getProperty("nuaforma.pdf.validation"))
        val classpath = Files.list(validation).use { paths -> paths.filter { it.toString().endsWith(".jar") }.sorted().toList() }
        return DocxPdfProvider(NativePdfConfiguration(fonts, PdfProcessValidator(Path.of(System.getProperty("nuaforma.pdf.validation.java")), classpath, temporary)))
    }

    private fun generate(provider: DocxPdfProvider, source: ByteArray, profile: String): ByteArray {
        val output = ByteArrayOutputStream()
        provider.generate(GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
            FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile)))), output, GenerationContext())
        return output.toByteArray()
    }

    private fun glyphs(document: PDDocument): List<TextPosition> {
        val result = mutableListOf<TextPosition>()
        val reader = object : PDFTextStripper() {

            override fun processTextPosition(text: TextPosition) {
                if (text.font.name.contains("FontAwesome")) result += text
                super.processTextPosition(text)
            }

        }
        reader.getText(document)
        return result
    }

    @ParameterizedTest
    @EnumSource(PdfFontEmbedding::class)
    fun `private glyphs retain exact alias-control pixels and original characters in independently validated PDF profiles`(embedding: PdfFontEmbedding) {
        val provider = configured(embedding)
        val output = Files.createDirectories(Path.of("build/258-private-glyphs/${embedding.name.lowercase()}"))
        for (paper in listOf("a4", "letter")) {
            val folder = "/private-glyphs/$paper/"
            val source = bytes(folder + "source.docx")
            val original = source.copyOf()
            val control = bytes(folder + "control-source.docx")
            val reference = bytes(folder + "control-word-reference.pdf")
            val wordProof = bytes(folder + "control-word-reference.pdf.provenance.json").decodeToString()
            val proof = bytes(folder + "golden-provenance.json").decodeToString()
            val golden = bytes(folder + "page-1.png")
            assertTrue(wordProof.contains(hash(control)) && wordProof.contains(hash(reference)))
            for (value in listOf(source, control, reference, golden)) assertTrue(proof.contains(hash(value)))
            val clean = ByteArrayOutputStream()
            assertTrue(provider.cleanTemplate(source.inputStream(), clean, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))).changes.isEmpty())
            assertArrayEquals(source, clean.toByteArray())
            Loader.loadPDF(reference).use { word ->
                val expected = PDFTextStripper().getText(word).map { equivalents[it] ?: it }.joinToString("").replace(Regex("\\s+"), " ").trim()
                val expectedGlyphs = glyphs(word)
                assertEquals(4, expectedGlyphs.size)
                for (profile in listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b")) {
                    val rendered = generate(provider, source, profile)
                    Files.write(output.resolve("$paper-$profile.pdf"), rendered)
                    Loader.loadPDF(rendered).use { actual ->
                        assertEquals(1, actual.numberOfPages)
                        assertEquals(expected, PDFTextStripper().getText(actual).replace(Regex("\\s+"), " ").trim())
                        val actualGlyphs = glyphs(actual)
                        assertEquals(4, actualGlyphs.size)
                        for ((before, after) in expectedGlyphs.zip(actualGlyphs)) {
                            assertEquals(equivalents.getValue(before.unicode.single()).toString(), after.unicode)
                            assertEquals(before.xDirAdj, after.xDirAdj, .2f)
                            assertEquals(before.yDirAdj, after.yDirAdj, .1f)
                            assertEquals(before.widthDirAdj, after.widthDirAdj, .05f)
                        }
                        val page = actual.getPage(0)
                        val embedded = page.resources.fontNames.map { page.resources.getFont(it) }
                        assertTrue(embedded.all { it.isEmbedded })
                        assertEquals(embedding == PdfFontEmbedding.SUBSET, embedded.single { it.name.contains("FontAwesome") }.name.contains('+'))
                        assertImagesMatch(ImageIO.read(golden.inputStream()), PDFRenderer(actual).renderImageWithDPI(0, 96f)) {
                            pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                        }
                    }
                }
            }
            assertArrayEquals(original, source)
        }
    }

    @Test
    fun `a missing symbol family fails without publishing bytes instead of selecting another font`() {
        val output = ByteArrayOutputStream()
        val source = bytes("/private-glyphs/a4/source.docx")
        val provider = configured(PdfFontEmbedding.SUBSET, symbols = false)
        assertThrows(GenerationException::class.java) {
            provider.generate(GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                FormatRequest(DocumentFormat.PDF, mapOf("profile" to "standard")))), output, GenerationContext())
        }
        assertEquals(0, output.size())
    }

}
