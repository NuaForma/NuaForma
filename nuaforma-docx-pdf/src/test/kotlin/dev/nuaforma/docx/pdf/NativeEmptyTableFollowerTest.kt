package dev.nuaforma.docx.pdf

import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class NativeEmptyTableFollowerTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `an empty zero-height table follower occupies no body space`() {
        val support = NativeWordFeatureSupport(temporary)
        val parts = PdfFixture.unzip(support.bytes(source))
        val original = parts.getValue(part).decodeToString()

        fun image(insert: String) = Loader.loadPDF(support.generate(
            PdfFixture.zip(parts + (part to afterTable(original, insert).toByteArray())), "standard"
        )).use { PDFRenderer(it).renderImageWithDPI(0, 96f) }

        assertImagesMatch(image(""), image(empty())) {
            pixelTolerance = 0.0
            allowedDifferentPixelsPercent = 0.0
            threshold = 1
        }
    }

    @Test
    fun `zero line height cannot swallow text or author-supplied paragraph spacing`() {
        val support = NativeWordFeatureSupport(temporary)
        support.rejectChanged(source, part, "spacing") {
            afterTable(it, empty().replace("</w:p>", "<w:r><w:t>VISIBLE</w:t></w:r></w:p>"))
        }
        support.rejectChanged(source, part, "spacing") {
            afterTable(it, empty().replace("w:before=\"0\"", "w:before=\"320\""))
        }
    }

    private fun afterTable(xml: String, insert: String) = xml.replaceFirst("</w:tbl>",
        "</w:tbl>$insert<w:p><w:r><w:t>AFTER_TABLE_MARKER</w:t></w:r></w:p>")

    private fun empty() = """<w:p><w:pPr><w:spacing w:before="0" w:after="0" w:line="0" w:lineRule="auto"/>""" +
        """<w:rPr><w:rFonts w:ascii="Arial" w:hAnsi="Arial"/><w:sz w:val="20"/><w:szCs w:val="20"/></w:rPr></w:pPr></w:p>"""

    private companion object {
        const val source = "/table-style-defaults/a4/source.docx"
        const val part = "word/document.xml"
    }
}
