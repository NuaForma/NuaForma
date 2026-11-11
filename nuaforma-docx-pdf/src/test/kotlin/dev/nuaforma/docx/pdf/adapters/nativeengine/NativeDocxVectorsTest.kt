package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeDocxVectorsTest {
    private val xml = """<w:drawing xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
        xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
        xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape">
        <wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="127000" cy="127000"/>
        <wp:docPr id="1" name="Arbitrary caller name"/><a:graphic><a:graphicData uri="http://schemas.microsoft.com/office/word/2010/wordprocessingShape">
        <wps:wsp><wps:cNvSpPr/><wps:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="127000" cy="127000"/></a:xfrm>
        <a:custGeom><a:avLst/><a:gdLst/><a:ahLst/><a:cxnLst/><a:rect l="0" t="0" r="1000" b="1000"/>
        <a:pathLst><a:path w="1000" h="1000" fill="none" stroke="1" extrusionOk="0">
        <a:moveTo><a:pt x="100" y="100"/></a:moveTo><a:lnTo><a:pt x="900" y="900"/></a:lnTo>
        </a:path></a:pathLst></a:custGeom><a:noFill/>
        <a:ln w="8890" cap="rnd" cmpd="sng" algn="ctr"><a:solidFill><a:srgbClr val="127654"/></a:solidFill>
        <a:prstDash val="solid"/><a:round/></a:ln></wps:spPr><wps:bodyPr/></wps:wsp>
        </a:graphicData></a:graphic></wp:inline></w:drawing>"""

    private fun read(source: String) = NativeDocxVectors({}, { throw IllegalStateException(it) })
        .read(ConversionXml(GenerationContext()).read(source.toByteArray()))

    @Test
    fun `nested vectors never change the classification of a containing drawing`() {
        val reader = NativeDocxVectors({}, { throw IllegalStateException(it) })
        val parser = ConversionXml(GenerationContext())
        assertTrue(reader.isVector(parser.read(xml.toByteArray())))
        val outer = xml.replace(Regex("<a:custGeom>[\\s\\S]*?</a:custGeom>"), "<a:prstGeom/>")
            .replace("<wps:bodyPr/>", "<wps:bodyPr>$xml</wps:bodyPr>")
        assertFalse(reader.isVector(parser.read(outer.toByteArray())))
    }


    @Test
    fun `bounded geometry is accepted by structure regardless of drawing name`() {
        val run = read(xml)
        assertEquals(10f, run.width)
        assertEquals(70, run.vector.strokeWidth)
        assertEquals(0x76, run.color.green)
        assertEquals(900, run.vector.paths.single().points.last().x)
        assertEquals(10f, read(xml.replace("Arbitrary caller name", "NuaFormaAnything")).height)
    }

    @Test
    fun `effects links text transforms fills and malformed geometry cannot bypass the grammar`() {
        val mutations = listOf(
            xml.replace("<wps:bodyPr/>", "<wps:bodyPr><a:solidFill/></wps:bodyPr>"),
            xml.replace("<a:noFill/>", "<a:solidFill><a:srgbClr val=\"ffffff\"/></a:solidFill>"),
            xml.replace("<a:xfrm>", "<a:xfrm rot=\"1\">"),
            xml.replace("fill=\"none\"", "fill=\"norm\""),
            xml.replace("x=\"900\"", "x=\"1001\""),
            xml.replace("x=\"100\"", "x=\"0\""),
            xml.replace("x=\"100\"", "x=\"1e2\""),
            xml.replace("<a:round/>", "<a:miter/>"),
            xml.replace("w=\"8890\"", "w=\"8891\""),
            xml.replace("<wps:cNvSpPr/>", "<wps:cNvSpPr><a:hlinkClick/></wps:cNvSpPr>"),
            xml.replace("<wps:bodyPr/>", "<wps:bodyPr>PRIVATE_UNEXPECTED_TEXT</wps:bodyPr>"),
            xml.replace("cy=\"127000\"", "cy=\"254000\""),
        )
        for (mutation in mutations) {
            val error = assertThrows(IllegalStateException::class.java) { read(mutation) }
            assertFalse(error.message.orEmpty().contains("PRIVATE_UNEXPECTED_TEXT"))
        }
    }
}
