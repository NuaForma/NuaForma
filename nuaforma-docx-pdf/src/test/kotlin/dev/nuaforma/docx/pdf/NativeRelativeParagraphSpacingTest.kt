package dev.nuaforma.docx.pdf

import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class NativeRelativeParagraphSpacingTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `line-unit gaps override physical fallbacks and collapse in the ordinary paragraph flow`() {
        val support = NativeWordFeatureSupport(temporary)
        val parts = PdfFixture.unzip(support.bytes(source))
        val document = parts.getValue(part).decodeToString()

        fun image(after: String, before: String, withStyle: Boolean = false): java.awt.image.BufferedImage {
            val replacement = body(document, after, before, withStyle).toByteArray()
            val fixture = parts + (part to replacement) + if (withStyle) {
                parts.filterKeys { it in styleParts }.mapValues { (_, bytes) ->
                    bytes.decodeToString().replace("</w:styles>", relativeStyle + "</w:styles>").toByteArray()
                }
            } else emptyMap()
            return Loader.loadPDF(support.generate(PdfFixture.zip(fixture), "standard")).use {
                PDFRenderer(it).renderImageWithDPI(0, 96f)
            }
        }

        val expected = image("""w:after="96"""", """w:before="192"""")
        for (actual in listOf(
            image("""w:after="1680" w:afterLines="40"""", """w:before="1600" w:beforeLines="80""""),
            image("""w:after="1680"""", """w:before="0" w:beforeLines="80"""", withStyle = true),
        )) {
            assertImagesMatch(expected, actual) {
                pixelTolerance = 0.0
                allowedDifferentPixelsPercent = 0.0
                threshold = 1
            }
        }
    }

    @Test
    fun `invalid original line units are reported at paragraph spacing`() {
        val support = NativeWordFeatureSupport(temporary)
        for (invalid in listOf("-1", "bogus", "120001")) {
            support.rejectChanged(source, part, "spacing") {
                body(it, """w:afterLines="$invalid"""", """w:before="0"""", false)
            }
        }
    }

    private fun body(xml: String, after: String, before: String, useStyle: Boolean): String {
        val style = if (useStyle) """<w:pStyle w:val="RelativeProbe"/>""" else ""
        val first = """<w:p><w:pPr>$style<w:spacing w:before="0" $after w:line="360" w:lineRule="auto"/></w:pPr>""" +
            """<w:r><w:rPr><w:sz w:val="28"/></w:rPr><w:t>FIRST_MARKER</w:t></w:r></w:p>"""
        val second = """<w:p><w:pPr><w:spacing $before w:after="0" w:line="240" w:lineRule="auto"/></w:pPr>""" +
            "<w:r><w:t>SECOND_MARKER</w:t></w:r></w:p>"
        return xml.substringBefore("<w:body>") + "<w:body>$first$second<w:sectPr>" + xml.substringAfter("<w:sectPr>")
    }

    private companion object {
        const val source = "/soft-line-breaks/table-bands/a4/source.docx"
        const val part = "word/document.xml"
        val styleParts = setOf("word/styles.xml", "word/stylesWithEffects.xml")
        const val relativeStyle = """<w:style w:type="paragraph" w:styleId="RelativeProbe"><w:name w:val="RelativeProbe"/>
            <w:basedOn w:val="Normal"/><w:pPr><w:spacing w:afterLines="40"/></w:pPr></w:style>"""
    }
}
