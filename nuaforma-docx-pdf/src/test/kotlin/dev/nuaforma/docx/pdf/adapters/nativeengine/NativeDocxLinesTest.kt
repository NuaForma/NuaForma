package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.pdf.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeDocxLinesTest {
    private val page = PdfPage(600f, 800f, PdfMargins(60f, 60f, 60f, 60f))
    private var current: XmlElement? = null
    private val reader = NativeDocxLines({ current = it }) { throw IllegalArgumentException(it) }
    private fun xml(body: String) = ConversionXml(GenerationContext()).read("""<w:pict xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:v="urn:schemas-microsoft-com:vml">$body</w:pict>""".toByteArray())
    private fun vml(style: String = "position:absolute", from: String = "1in,2cm", to: String = "144pt,20mm", extra: String = "", child: String = "") =
        xml("""<v:line from="$from" to="$to" style="$style" $extra>$child</v:line>""")

    @Test fun `VML physical units default anchors and explicit page or paragraph coordinates resolve`() {
        val default = reader.drawing(vml(), page)
        assertEquals(132f, default.fromX); assertEquals(72 / 2.54f * 2, default.fromY, .001f)
        assertTrue(default.pageX); assertFalse(default.pageY); assertEquals(.75f, default.stroke.width)
        val fixed = reader.drawing(vml("position:absolute;mso-position-horizontal-relative:page;mso-position-vertical-relative:margin;z-index:-1", extra = "strokeweight=\"2pt\" strokecolor=\"white\""), page)
        assertEquals(72f, fixed.fromX); assertEquals(default.fromY + 60f, fixed.fromY)
        assertTrue(fixed.pageY); assertTrue(fixed.behindText); assertEquals(PdfColor(255, 255, 255), fixed.stroke.color)
        val relative = reader.drawing(vml("position:absolute;margin-left:-2pt;margin-top:.5pt;mso-position-horizontal-relative:text", child = "<v:stroke dashstyle=\"shortDot\"/>"), page)
        assertEquals(70f, relative.fromX); assertEquals(default.fromY + .5f, relative.fromY)
        assertFalse(relative.pageX); assertEquals(PdfLineStyle.DOTTED, relative.stroke.style)
        val shortDash = reader.drawing(vml(extra = "strokeweight=\"1.5pt\"", child = "<v:stroke dashstyle=\"shortDash\"/>"), page).stroke
        assertEquals(4.5f, shortDash.dashOn); assertEquals(1.5f, shortDash.dashOff)
        assertTrue(reader.isLine(vml())); assertFalse(reader.isLine(xml("<v:rect/>")))
    }

    @Test fun `VML invalid dimensions duplicate styles wrapping arrows and active children reject`() {
        val cases = listOf(
            vml("position:absolute;position:absolute"), vml("position:relative"),
            vml("position:absolute;unknown:1"), vml("invalid"),
            vml("position:absolute;mso-position-horizontal-relative:char"), vml(from = "1pt"),
            vml(from = "1px,2pt"), vml(extra = "href=\"https://invalid.example/\""),
            vml(child = "<v:stroke endarrow=\"block\"/>"), vml(child = "<v:fill/>"),
            vml(child = "<v:stroke dashstyle=\"dashDot\"/>"), vml(extra = "strokecolor=\"not-a-color\""),
            xml("<v:rect from=\"0pt,0pt\"/>")
        )
        cases.forEach { value -> assertThrows(IllegalArgumentException::class.java) { reader.drawing(value, page) } }
        assertNotNull(current)
    }

    @Test fun `border cascade replaces individual edges and nil clears inherited decoration`() {
        fun borders(content: String) = ConversionXml(GenerationContext()).read("""<w:pBdr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">$content</w:pBdr>""".toByteArray())
        val top = reader.borders(borders("<w:top w:val=\"single\"/>"), null)
        assertEquals(1f, top.top!!.stroke.width)
        val both = reader.borders(borders("<w:bottom w:val=\"double\" w:sz=\"16\" w:space=\"3\"/>"), top)
        assertEquals(top.top, both.top); assertEquals(6f, both.bottom!!.stroke.extent)
        val cleared = reader.borders(borders("<w:top w:val=\"none\"/><w:bottom w:val=\"nil\"/>"), both)
        assertNull(cleared.top); assertNull(cleared.bottom)
        for (content in listOf("<w:bar w:val=\"single\"/>", "<w:top/><w:top/>", "<w:top w:val=\"single\" w:sz=\"bad\"/>", "<w:top w:val=\"single\" w:color=\"red\"/>", "<w:top w:val=\"single\"><w:child/></w:top>"))
            assertThrows(IllegalArgumentException::class.java) { reader.borders(borders(content), null) }
    }
}
