package dev.nuaforma.docx.pdf

import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class NativeEmptyLegacyBandFrameTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `an empty legacy frame consumes no flow space in a page band`() {
        val support = NativeWordFeatureSupport(temporary)
        val base = PdfFixture.unzip(support.bytes(source))
        val control = base.toMutableMap()
        val framed = base.toMutableMap()
        for ((part, root, marker) in listOf(Triple("word/header1.xml", "hdr", "BAND_HEADER"), Triple("word/footer1.xml", "ftr", "BAND_FOOTER"))) {
            val xml = base.getValue(part).decodeToString()
            control[part] = band(xml, root, marker, "").toByteArray()
            framed[part] = band(xml, root, marker, emptyFrame("")).toByteArray()
        }
        Loader.loadPDF(support.generate(PdfFixture.zip(control), "standard")).use { before ->
            Loader.loadPDF(support.generate(PdfFixture.zip(framed), "standard")).use { after ->
                val markers = Regex("BAND_HEADER|BAND_FOOTER")
                val positions = support.positions(before, markers)
                assertEquals(2, positions.size)
                assertEquals(positions, support.positions(after, markers))
                assertImagesMatch(PDFRenderer(before).renderImageWithDPI(0, 96f), PDFRenderer(after).renderImageWithDPI(0, 96f)) {
                    pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                }
            }
        }
    }

    @Test
    fun `a legacy frame with text or different geometry remains source-diagnosed`() {
        val support = NativeWordFeatureSupport(temporary)
        for (frame in listOf(emptyFrame("<w:r><w:t>VISIBLE</w:t></w:r>"),
            emptyFrame("").replace("""w:y="1"""", """w:y="120""""))) {
            support.rejectChanged(source, "word/header1.xml", "framePr") { band(it, "hdr", "HEADER", frame) }
        }
    }

    private fun band(xml: String, root: String, marker: String, prefix: String): String {
        val start = requireNotNull(Regex("(.*?<w:$root\\b[^>]*>)", RegexOption.DOT_MATCHES_ALL).find(xml)).value
        return start + prefix + """<w:p><w:pPr><w:spacing w:before="0" w:after="0" w:line="280" w:lineRule="exact"/></w:pPr>""" +
            "<w:r><w:t>$marker</w:t></w:r></w:p></w:$root>"
    }

    private fun emptyFrame(content: String): String =
        """<w:p><w:pPr><w:pStyle w:val="Normal"/><w:framePr w:wrap="around" w:vAnchor="text" w:hAnchor="margin" w:xAlign="center" w:y="1"/></w:pPr>$content</w:p>"""

    private companion object {
        const val source = "/soft-line-breaks/table-bands/a4/source.docx"
    }
}
