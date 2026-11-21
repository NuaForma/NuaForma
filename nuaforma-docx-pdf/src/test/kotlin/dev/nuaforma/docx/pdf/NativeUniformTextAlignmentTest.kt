package dev.nuaforma.docx.pdf

import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class NativeUniformTextAlignmentTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `alignment modes preserve qualified uniform natural text lines`() {
        val support = NativeWordFeatureSupport(temporary)
        val original = PdfFixture.unzip(support.bytes(source))
        val document = original.getValue(part).decodeToString()

        fun image(alignment: String) = Loader.loadPDF(support.generate(
            PdfFixture.zip(original + (part to fixture(document, alignment, 20, "auto").toByteArray())), "standard",
        )).use { PDFRenderer(it).renderImageWithDPI(0, 96f) }

        val control = image("baseline")
        for (alignment in listOf("top", "center", "bottom")) {
            assertImagesMatch(control, image(alignment)) {
                pixelTolerance = 0.0
                allowedDifferentPixelsPercent = 0.0
                threshold = 1
            }
        }
    }

    @Test
    fun `mixed metrics and fixed line boxes retain a diagnostic for active alignment`() {
        val support = NativeWordFeatureSupport(temporary)
        support.rejectChanged(source, part, "textAlignment") { fixture(it, "top", 36, "auto") }
        support.rejectChanged(source, part, "textAlignment") { fixture(it, "center", 20, "exact") }
    }

    private fun fixture(xml: String, align: String, nextSize: Int, rule: String): String {
        val spacing = if (rule == "auto") 240 else 480
        val paragraph = """<w:p><w:pPr><w:spacing w:before="0" w:after="0" w:line="$spacing" w:lineRule="$rule"/>""" +
            """<w:textAlignment w:val="$align"/></w:pPr>""" +
            run("LEFT_WORD ", 20) + run("RIGHT_WORD", nextSize) + "</w:p>"
        return xml.substringBefore("<w:body>") + "<w:body>$paragraph<w:sectPr>" + xml.substringAfter("<w:sectPr>")
    }

    private fun run(text: String, size: Int) =
        """<w:r><w:rPr><w:sz w:val="$size"/></w:rPr><w:t xml:space="preserve">$text</w:t></w:r>"""

    private companion object {
        const val source = "/floating-drawings/control/a4/source.docx"
        const val part = "word/document.xml"
    }
}
