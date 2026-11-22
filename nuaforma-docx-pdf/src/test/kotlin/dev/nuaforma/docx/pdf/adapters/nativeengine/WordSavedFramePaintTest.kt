package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
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

    private fun paint(paint: String, stored: String) = ConversionXml(GenerationContext()).read(
        ("""<wps:spPr xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape" """ +
            """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:a14="http://schemas.microsoft.com/office/drawing/2010/main">""" +
            "$paint<a:extLst>$stored</a:extLst></wps:spPr>").toByteArray()
    )

    private fun reject(node: XmlElement, message: String): Nothing = throw IllegalArgumentException("${node.name} $message")
}
