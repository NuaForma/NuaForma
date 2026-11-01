package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
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
import javax.imageio.ImageIO
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch

class WordFormattingProviderTest {
    @TempDir lateinit var temporary: Path

    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes() }

    private fun provider(): NativeDocxPdfProvider {
        val fonts = listOf(NativePdfFont("Noto Serif", "regular", resource("/own-engine-fonts/NotoSerif-Regular.ttf"))) +
            listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic", "boldItalic" to "BoldItalic").map { (face, file) ->
                NativePdfFont("Liberation Sans", face, resource("/own-engine-fonts/LiberationSans-$file.ttf"))
            }
        val jars = Files.list(Path.of(System.getProperty("nuaforma.pdf.validation"))).use { paths -> paths.filter { it.toString().endsWith(".jar") }.sorted().toList() }
        val validator = PdfProcessValidator(Path.of(System.getProperty("nuaforma.pdf.validation.java")), jars, temporary)
        return NativeDocxPdfProvider(NativePdfConfiguration(fonts, validator))
    }

    @Test fun `filled DOCX serialization font collection and all archival routes retain qualified formatting`() {
        val provider = provider()
        val cases = listOf("font-features/modern-enabled", "paragraph-spacing/a4-fixed-edges",
            "mirror-indents/legacy-flip", "conditional-tables/a4")
        for (case in cases) {
            val profiles = if (case.startsWith("font-features")) listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard")
            for (profile in profiles) {
                val bytes = ByteArrayOutputStream().also { output ->
                    provider.generate(GenerationRequest(resource("/$case/source.docx").inputStream(),
                        GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile)))),
                        output, GenerationContext())
                }.toByteArray()
                Loader.loadPDF(bytes).use { pdf ->
                    val renderer = PDFRenderer(pdf)
                    Loader.loadPDF(resource("/$case/word-reference.pdf")).use { word ->
                        assertEquals(word.numberOfPages, pdf.numberOfPages, "$case $profile page count")

                        fun text(value: org.apache.pdfbox.pdmodel.PDDocument) = PDFTextStripper().getText(value).replace(Regex("\\s+"), " ").trim()

                        assertEquals(text(word), text(pdf), "$case $profile complete logical text")
                    }
                    for (page in 0 until pdf.numberOfPages) {
                        val expected = ImageIO.read(resource("/$case/page-${page + 1}.png").inputStream())
                        assertImagesMatch(expected, renderer.renderImageWithDPI(page, 96f), "$case $profile page ${page + 1}") {
                            pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                        }
                    }
                }
            }
        }
        assertEquals(0, Files.list(temporary).use { it.count() })
    }

}
