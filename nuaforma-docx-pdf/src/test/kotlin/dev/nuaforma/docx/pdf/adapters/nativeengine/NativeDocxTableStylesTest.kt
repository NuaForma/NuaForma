package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeDocxTableStylesTest {
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val context = GenerationContext()

    private fun xml(text: String) = ConversionXml(context).read(text.toByteArray())

    private fun resolver(styles: String, props: String = "") = NativeDocxTableStyles(
        listOf(xml("""<w:style xmlns:w="$word">$styles</w:style>""")),
        xml("""<w:tblPr xmlns:w="$word">$props</w:tblPr>"""), 6, 4, 2, context) { _, message ->
            throw NativeDocxReadException(message, "word/styles.xml")
        }

    private fun region(kind: String, content: String = "") = """<w:tblStylePr w:type="$kind">$content</w:tblStylePr>"""

    @Test fun `region order is independent of XML declaration order and missing band sizes disable banding`() {
        val kinds = listOf("nwCell", "firstRow", "firstCol", "band1Vert", "band1Horz", "wholeTable")
        val source = kinds.joinToString("") { region(it) }
        val properties = """<w:tblLook w:val="01E0"/>"""
        val styles = resolver(source, properties)
        assertEquals(listOf("wholeTable", "firstCol", "firstRow", "nwCell"), styles.layers(0, 0).drop(1).map { it.attribute(word, "type") })
        assertEquals(listOf("wholeTable"), styles.layers(2, 1).drop(1).map { it.attribute(word, "type") })
        assertTrue(styles.active)
        val disabled = resolver(source, """<w:tblLook w:val="01E0" w:firstRow="0" w:firstColumn="off"/>""")
        assertEquals(listOf("wholeTable"), disabled.layers(0, 0).drop(1).map { it.attribute(word, "type") })
    }

    @Test fun `unknown duplicate nested and malformed active table styling rejects explicitly`() {
        val cases = listOf(region("unknown"), region("firstRow") + region("firstRow"),
            region("firstRow", "<w:tblPr/>"), region("firstRow", "<w:trPr/>"),
            region("firstRow", "<w:tcPr><w:textDirection/></w:tcPr>"),
            region("firstRow").replace("w:type=", "extra=\"1\" w:type="),
            "<w:tblPr><w:tblStyleRowBandSize w:val=\"4\"/></w:tblPr>",
            "<w:tblPr><w:tblStyleColBandSize w:val=\"invalid\"/></w:tblPr>",
            "<w:tblPr><w:tblStyleRowBandSize w:val=\"1\"><w:rPr/></w:tblStyleRowBandSize></w:tblPr>")
        for (source in cases) assertThrows(NativeDocxReadException::class.java) { resolver(source) }
        for (props in listOf("<w:tblLook w:val=\"xyz\"/>", "<w:tblLook w:val=\"FFFF\"/>",
            "<w:tblLook w:firstRow=\"invalid\"/>", "<w:tblLook extra=\"1\"/>", "<w:tblLook>hidden</w:tblLook>"))
            assertThrows(NativeDocxReadException::class.java) { resolver(region("firstRow"), props).layers(0, 0) }
    }

}
