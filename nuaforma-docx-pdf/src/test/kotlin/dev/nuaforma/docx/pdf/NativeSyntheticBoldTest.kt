package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.pdf.PdfProcessValidator
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode
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

class NativeSyntheticBoldTest {
    @TempDir lateinit var temporary: Path

    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes() }

    private fun provider(lightItalic: Boolean = true): DocxPdfProvider {
        val fonts = mutableListOf(NativePdfFont("Noto Serif Light", "regular", resource("/own-engine-fonts/NotoSerif-Light.ttf")))
        if (lightItalic) fonts += NativePdfFont("Noto Serif Light", "italic", resource("/own-engine-fonts/NotoSerif-LightItalic.ttf"))
        for ((face, file) in listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic", "boldItalic" to "BoldItalic"))
            fonts += NativePdfFont("Liberation Sans", face, resource("/own-engine-fonts/LiberationSans-$file.ttf"))
        val validator = PdfProcessValidator(Path.of(System.getProperty("nuaforma.pdf.validation.java")),
            Files.list(Path.of(System.getProperty("nuaforma.pdf.validation"))).use { paths -> paths.filter { it.toString().endsWith(".jar") }.sorted().toList() }, temporary)
        return DocxPdfProvider(NativePdfConfiguration(fonts, validator))
    }

    private fun request(input: ByteArray, profile: String) = GenerationRequest(input.inputStream(), GenerationRequirements(
        FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile))))

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun glyphs(bytes: ByteArray): List<Triple<String, Float, Float>> {
        val result = mutableListOf<Triple<String, Float, Float>>()
        Loader.loadPDF(bytes).use { pdf ->
            object : PDFTextStripper() {

                override fun processTextPosition(position: TextPosition) {
                    if (position.unicode.isNotBlank()) result += Triple(position.unicode, position.xDirAdj, position.yDirAdj)
                    super.processTextPosition(position)
                }

            }.getText(pdf)
        }
        return result
    }

    private fun syntheticModes(bytes: ByteArray): List<List<Boolean>> {
        val rows = sortedMapOf<Float, MutableList<Boolean>>()
        Loader.loadPDF(bytes).use { pdf ->
            object : PDFTextStripper() {

                override fun processTextPosition(position: TextPosition) {
                    if (position.unicode.isNotBlank()) rows.getOrPut(position.yDirAdj) { mutableListOf() } +=
                        graphicsState.textState.renderingMode == RenderingMode.FILL_STROKE
                    super.processTextPosition(position)
                }

            }.getText(pdf)
        }
        return rows.values.toList()
    }

    @Test
    fun `same-family synthetic bold matches actual Word with real bold faces preferred in every validated profile`() {
        val source = resource("/synthetic-bold/source.docx")
        val reference = resource("/synthetic-bold/word-reference.pdf")
        val provenance = resource("/synthetic-bold/word-reference.pdf.provenance.json").decodeToString()
        assertTrue(provenance.contains(hash(source)))
        assertTrue(provenance.contains(hash(reference)))
        val golden = resource("/synthetic-bold/page-1.png")
        val goldenProvenance = resource("/synthetic-bold/golden-provenance.json").decodeToString()
        assertTrue(goldenProvenance.contains(hash(source)))
        assertTrue(goldenProvenance.contains(hash(reference)))
        assertTrue(goldenProvenance.contains(hash(golden)))
        val expected = glyphs(reference)
        val provider = provider()
        val directory = Files.createDirectories(Path.of("build/234-synthetic-bold"))
        for (profile in listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b")) {
            val output = ByteArrayOutputStream()
            provider.generate(request(source, profile), output, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
            val bytes = output.toByteArray()
            Files.write(directory.resolve("$profile.pdf"), bytes)
            val actual = glyphs(bytes)
            assertEquals(expected.map { it.first }, actual.map { it.first })
            for (index in expected.indices) {
                // Word's rounded glyph advances stay within one pixel of the 96-DPI review.
                assertEquals(expected[index].second, actual[index].second, .75f, "$profile glyph $index x")
                assertEquals(expected[index].third, actual[index].third, .4f, "$profile glyph $index baseline")
            }
            Loader.loadPDF(bytes).use { pdf ->
                assertEquals(1, pdf.numberOfPages)
                val page = pdf.getPage(0)
                val modes = syntheticModes(bytes)
                assertEquals(9, modes.size)
                for (line in 0..7) assertTrue(modes[line].all { it == (line == 1 || line == 3) }, "$profile line $line synthetic selection")
                assertEquals(List(11) { true } + List(14) { false }, modes.last(), "$profile colored bold followed by plain")
                assertTrue(page.resources.fontNames.all { page.resources.getFont(it).isEmbedded })
                val names = page.resources.fontNames.map { page.resources.getFont(it).name }
                assertTrue(names.any { it.endsWith("LiberationSans-Bold") })
                assertTrue(names.any { it.endsWith("LiberationSans-BoldItalic") })
                assertTrue(names.any { it.endsWith("NotoSerif-LightItalic") })
                val raster = PDFRenderer(pdf).renderImageWithDPI(0, 96f)
                assertImagesMatch(ImageIO.read(golden.inputStream()), raster) {
                    pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                }
                ImageIO.write(raster, "png", directory.resolve("$profile.png").toFile())
            }
        }
    }

    @Test
    fun `synthetic bold cannot invent a missing italic face or a missing family`() {
        val source = resource("/synthetic-bold/source.docx")
        val boldItalicOnly = PdfFixture.parts().mapValues { (name, value) ->
            value.decodeToString().replace("DejaVu Sans", "Noto Serif Light").let {
                if (name == "word/document.xml") it.replace("<w:b/>", "<w:b/><w:i/>").replace("Hello {name}", "Only bold italic") else it
            }.toByteArray()
        }
        for (input in listOf(source, PdfFixture.zip(boldItalicOnly), PdfFixture.zip(PdfFixture.unzip(source).mapValues { (name, value) ->
            if (name == "word/document.xml") value.decodeToString().replace("Noto Serif Light", "Unavailable Family").toByteArray() else value
        }))) {
            val output = ByteArrayOutputStream()
            val error = assertThrows(GenerationException::class.java) { provider(false).generate(request(input, "standard"), output, GenerationContext()) }
            assertEquals(0, output.size())
            assertTrue(error.diagnostics.any { it.rejectionReason == RejectionReason.USED_FONT_FACE_UNAVAILABLE })
        }
    }

}
