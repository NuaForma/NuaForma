package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.pdf.PdfBorder
import dev.nuaforma.pdf.PdfColor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WordSavedFramePaintTest {
    private val storedFill = """<a:ext uri="{909E8E84-426E-40DD-AFC4-6F175D3DCCD1}"><a14:hiddenFill><a:solidFill><a:srgbClr val="D52C81"/></a:solidFill></a14:hiddenFill></a:ext>"""
    private val storedLine = """<a:ext uri="{91240B29-F687-4F45-9708-019B960494DF}"><a14:hiddenLine w="0"><a:solidFill><a:srgbClr val="000000"/></a:solidFill><a:headEnd/><a:tailEnd/></a14:hiddenLine></a:ext>"""
    private val storedEffects = """<a:ext uri="{AF507438-7753-43E0-B8FC-AC1667EBCBE1}"><a14:hiddenEffects><a:effectLst/></a14:hiddenEffects></a:ext>"""
    private val inactive = "<a:noFill/><a:ln><a:noFill/></a:ln><a:effectLst/>"

    @Test
    fun `disjoint saved fill outline and effects are passive together`() {
        val xml = paint(inactive, storedFill + storedLine + storedEffects)
        val consumed = WordFramePaint.passivePaintMetadata(xml, ::reject)
        assertEquals(setOf("extLst", "effectLst"), consumed.map { it.name.localPart }.toSet())
    }

    @Test
    fun `nonempty effects unrecognized payloads and competing visible paint are rejected`() {
        val invalid = listOf(
            paint(inactive, storedFill.repeat(2)),
            paint(inactive, storedLine.replace("w=\"0\"", "w=\"-1\"")),
            paint(inactive, storedEffects.replace("<a:effectLst/>", "<a:effectLst><a:blur/></a:effectLst>")),
            paint(inactive.replaceFirst("<a:noFill/>", ""), storedFill),
            paint(inactive.replace("<a:effectLst/>", "<a:effectLst><a:blur/></a:effectLst>"), storedEffects),
            paint(inactive, storedFill.replace("909E8E84", "909E8E85")),
        )
        for (properties in invalid) {
            assertThrows(IllegalArgumentException::class.java) { WordFramePaint.passivePaintMetadata(properties, ::reject) }
        }
    }

    @Test
    fun `rectangular strokes allow solid paint only within their physical width and outline bounds`() {
        val theme = WordThemeColors(null, ::reject)
        val fill = """<a:solidFill><a:srgbClr val="D52C81"/></a:solidFill>"""
        fun stroke(attributes: String, content: String) = WordFramePaint.stroke(
            paint("<a:ln $attributes>$content</a:ln>", "").elements().first(), theme, ::reject)

        val qualified = """<a:miter lim="141422"/><a:prstDash val="solid"/><a:headEnd type="none"/><a:tailEnd/>"""
        assertEquals(PdfBorder(.1f, PdfColor(213, 44, 129)), stroke("""w="1270" cap="sq" cmpd="sng" algn="ctr"""", fill + qualified))
        assertEquals(PdfBorder(4f, PdfColor(213, 44, 129)), stroke("""w="50800"""", fill + "<a:miter/>"))
        assertNull(stroke("", "<a:noFill/>"))
        for (invalid in listOf("", "w=\"1269\"", "w=\"50801\"", "w=\"0\"")) {
            val error = assertThrows(IllegalArgumentException::class.java) { stroke(invalid, fill) }
            assertTrue(error.message!!.endsWith("Frame ln/@w exceeds the qualified border bounds"))
        }
        for (invalid in listOf("cap=\"unknown\"", "cmpd=\"dbl\"", "algn=\"in\"", "a:w=\"1270\"", "unknown=\"1\"")) {
            val error = assertThrows(IllegalArgumentException::class.java) { stroke(invalid, fill) }
            assertTrue(error.message!!.endsWith("Only centered solid frame strokes are qualified"))
        }
        for ((property, reason) in listOf("<a:miter a:lim=\"800000\"/>" to "Frame miter changes the rectangular border corners",
            "<a:miter lim=\"141421\"/>" to "Frame miter changes the rectangular border corners",
            "<a:miter lim=\"2147483648\"/>" to "Frame miter changes the rectangular border corners",
            "<a:miter lim=\"NaN\"/>" to "Frame miter changes the rectangular border corners",
            "<a:prstDash val=\"dot\"/>" to "Dashed frame strokes are not qualified",
            "<a:prstDash val=\"solid\"><a:ln/></a:prstDash>" to "Dashed frame strokes are not qualified",
            "<a:prstDash val=\"solid\" extra=\"1\"/>" to "Dashed frame strokes are not qualified",
            "<a:miter/><a:miter/>" to "Unsupported or duplicate frame stroke feature",
            "<a14:miter/>" to "Unsupported or duplicate frame stroke feature")) {
            val error = assertThrows(IllegalArgumentException::class.java) { stroke("w=\"6350\"", fill + property) }
            assertTrue(error.message!!.endsWith(reason))
        }
    }

    private fun paint(paint: String, stored: String) = ConversionXml(GenerationContext()).read(
        ("""<wps:spPr xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape" """ +
            """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:a14="http://schemas.microsoft.com/office/drawing/2010/main">""" +
            "$paint<a:extLst>$stored</a:extLst></wps:spPr>").toByteArray()
    )

    private fun reject(node: XmlElement, message: String): Nothing = throw IllegalArgumentException("${node.name} $message")
}
