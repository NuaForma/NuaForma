package dev.nuaforma.docx.pdf

import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class NativeZeroTextOutlineTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `zero-width colored outline retains the text positions and original raster`() {
        val support = NativeWordFeatureSupport(temporary)
        val base = PdfFixture.unzip(support.bytes(source))
        val xml = base.getValue(part).decodeToString()
        val pattern = Regex("Outline *proof *123")
        for (color in listOf("000000", "FF0000", null)) {
            val control = support.generate(PdfFixture.zip(base + (part to fixture(xml, "").toByteArray())), "standard")
            val outlined = support.generate(PdfFixture.zip(base + (part to fixture(xml, outline("0", color)).toByteArray())), "standard")
            Loader.loadPDF(control).use { before ->
                Loader.loadPDF(outlined).use { after ->
                    val expected = support.positions(before, pattern)
                    assertEquals(1, expected.size)
                    assertEquals(expected, support.positions(after, pattern))
                    assertImagesMatch(PDFRenderer(before).renderImageWithDPI(0, 96f), PDFRenderer(after).renderImageWithDPI(0, 96f)) {
                        pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                    }
                }
            }
        }
    }

    @Test
    fun `visible and malformed outlines remain source-diagnosed`() {
        val support = NativeWordFeatureSupport(temporary)
        for (effect in listOf(outline("25400", "FF0000"), outline("0", "BAD-HEX"), outline("0", "FF0000").repeat(2))) {
            support.rejectChanged(source, part, "textOutline") { fixture(it, effect) }
        }
    }

    private fun outline(width: String, color: String?): String {
        val fill = color?.let { """<e:solidFill><e:srgbClr e:val="$it"/></e:solidFill>""" } ?: "<e:noFill/>"
        return """<e:textOutline xmlns:e="http://schemas.microsoft.com/office/word/2010/wordml" e:w="$width" e:cap="rnd" e:cmpd="sng" e:algn="ctr">""" +
            """$fill<e:prstDash e:val="solid"/><e:bevel/></e:textOutline>"""
    }

    private fun fixture(xml: String, effect: String): String = xml.substringBefore("<w:body>") +
        """<w:body><w:p><w:pPr><w:spacing w:before="0" w:after="0" w:line="1000" w:lineRule="exact"/></w:pPr>""" +
        """<w:r><w:rPr><w:sz w:val="76"/>$effect</w:rPr><w:t>Outline proof 123</w:t></w:r></w:p>""" +
        "<w:sectPr>" + xml.substringAfter("<w:sectPr>")

    private companion object {
        const val source = "/floating-drawings/control/a4/source.docx"
        const val part = "word/document.xml"
    }
}
