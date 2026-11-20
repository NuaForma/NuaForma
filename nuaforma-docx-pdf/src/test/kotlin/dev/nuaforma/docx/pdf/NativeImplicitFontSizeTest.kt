package dev.nuaforma.docx.pdf

import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path

class NativeImplicitFontSizeTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `omitted ordinary size matches the explicit ten point Word control`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        val parts = PdfFixture.unzip(support.bytes("/paragraph-formatting/ratio-228/$paper/source.docx")).toMutableMap()
        val size = Regex("""<w:sz(?:Cs)? w:val="[0-9]+"\s*/>""")
        val document = parts.getValue("word/document.xml").decodeToString().replace(size, "")
        val styles = parts.getValue("word/styles.xml").decodeToString().replace(size, "")
        parts["word/document.xml"] = document.toByteArray()

        fun render(defaultProperty: String) = Loader.loadPDF(support.generate(PdfFixture.zip(parts.also {
            it["word/styles.xml"] = styles.replaceFirst("</w:rPr>", "$defaultProperty</w:rPr>").toByteArray()
        }), "standard")).use { pdf ->
            PDFRenderer(pdf).renderImageWithDPI(0, 96f)
        }

        val explicit = render("""<w:sz w:val="20"/>""")
        for (sizeProperty in listOf("", """<w:szCs w:val="32"/>""")) {
            assertImagesMatch(explicit, render(sizeProperty)) {
                pixelTolerance = 0.0
                allowedDifferentPixelsPercent = 0.0
                threshold = 1
            }
        }
    }
}
