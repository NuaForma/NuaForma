package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordHiddenFrameOutlineTest {
    private val dml = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private val wps = "http://schemas.microsoft.com/office/word/2010/wordprocessingShape"
    private val drawing2010 = "http://schemas.microsoft.com/office/drawing/2010/main"
    private val xml = ConversionXml(GenerationContext())
    private val guid = "{91240B29-F687-4F45-9708-019B960494DF}"
    private val invisible = "<a:ln><a:noFill/></a:ln>"
    private val solid = """<a:solidFill><a:srgbClr val="000000"/></a:solidFill>"""

    private fun outline(visible: String, declaration: String? = guid, width: Int = 9525) =
        xml.read(("""
            <wps:spPr xmlns:wps="$wps" xmlns:a="$dml" xmlns:a14="$drawing2010">
              $visible
            """ + (declaration?.let { """
              <a:extLst><a:ext uri="$it"><a14:hiddenLine w="$width">$solid</a14:hiddenLine></a:ext></a:extLst>
            """ } ?: "") + "</wps:spPr>").toByteArray())

    @Test
    fun storedSolidOutlineIsEditingMetadataWhenTheVisibleLineHasNoFill() {
        assertEquals("extLst", WordFramePaint.passivePaintMetadata(outline(invisible)) { _, reason ->
            throw IllegalArgumentException(reason)
        }.single().name.localPart)
        assertTrue(WordFramePaint.passivePaintMetadata(outline(invisible, null)) { _, reason ->
            throw IllegalArgumentException(reason)
        }.isEmpty())
    }

    @Test
    fun malformedOrActiveLineDeclarationsAreExplicitlyRejected() {
        for (input in listOf(outline("""<a:ln w="9525">$solid</a:ln>"""), outline("", guid),
            outline(invisible, guid.replace("4DF", "4DA")), outline(invisible, guid, 999999))) {
            assertThrows(IllegalArgumentException::class.java) {
                WordFramePaint.passivePaintMetadata(input) { _, reason -> throw IllegalArgumentException(reason) }
            }
        }
    }
}
