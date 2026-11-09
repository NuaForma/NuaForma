package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordNumericSpellingsTest {
    private val context = GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))
    private val xml = ConversionXml(context)
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private fun root(body: String): XmlElement = xml.read("<w:document xmlns:w=\"$word\"><w:body>$body</w:body></w:document>".toByteArray())

    private fun normalize(root: XmlElement): Boolean {
        val source = NativeDocxSource(context)
        source.index(root, "word/document.xml")
        return WordNumericSpellings.normalizeSource(root) { node, message ->
            source.current = node
            throw source.failure(message, "word/document.xml")
        }
    }

    @Test
    fun `fractional grids padding and automatic line spacing truncate before integer binding`() {
        val root = root("""<w:tbl><w:tblPr><w:tblCellMar><w:top w:w="100.0" w:type="dxa"/><w:left w:w="100.8" w:type="dxa"/></w:tblCellMar></w:tblPr><w:tblGrid><w:gridCol w:w="819.2"/><w:gridCol w:w="1740.8000000000002"/><w:gridCol w:w="3379.2000000000003"/></w:tblGrid></w:tbl><w:p><w:pPr><w:spacing w:line="252.00000000000003"/></w:pPr></w:p>""")
        assertTrue(normalize(root))
        val leaves = root.descendants().filter { it.name.localPart in setOf("top", "left", "gridCol", "spacing") }.toList()
        assertEquals(listOf("100", "100", "819", "1740", "3379", "252"), leaves.map { it.attribute(word, if (it.name.localPart == "spacing") "line" else "w") })
        assertFalse(normalize(root))
    }

    @Test
    fun `invalid numeric spellings units bounds and structures retain precise original source`() {
        val invalid = listOf("-1", "NaN", "1e3", "1%", "288000.1", "9999999", "0", "1.0000000000000000001")
        for (value in invalid) {
            val failure = assertThrows(NativeDocxReadException::class.java) { normalize(root("""<w:tbl><w:tblGrid><w:gridCol w:w="$value"/></w:tblGrid></w:tbl>""")) }
            assertTrue(failure.sourceContext.path.orEmpty().endsWith("w:gridCol[1]"), value)
        }
        for (body in listOf(
            """<w:tbl><w:tblGrid><w:gridCol w:w="100.8"><w:extra/></w:gridCol></w:tblGrid></w:tbl>""",
            """<w:tbl><w:tblGrid><w:gridCol w:w="100.8" foreign="x"/></w:tblGrid></w:tbl>""",
            """<w:tbl><w:tblPr><w:tblCellMar><w:left w:w="100.8" w:type="pct"/></w:tblCellMar></w:tblPr></w:tbl>""",
            """<w:tbl><w:tblPr><w:tblCellMar><w:left w:w="0" w:type="nil"/></w:tblCellMar></w:tblPr></w:tbl>""",
            """<w:tbl><w:tblPr><w:tblCellMar><w:left w:w="1" w:type="dxa"/><w:left w:w="2" w:type="dxa"/></w:tblCellMar></w:tblPr></w:tbl>""",
            """<w:p><w:pPr><w:spacing w:line="252.8" w:lineRule="exact"/></w:pPr></w:p>""",
            """<w:p><w:pPr><w:spacing w:line="252.8"/><w:spacing w:line="240"/></w:pPr></w:p>"""
        )) assertThrows(NativeDocxReadException::class.java) { normalize(root(body)) }
    }

    @Test
    fun `integer exact spacing and foreign unrelated data remain unchanged`() {
        val root = root("""<w:p><w:pPr><w:spacing w:line="280" w:lineRule="exact"/></w:pPr></w:p><data xmlns="urn:unrelated"><gridCol w:w="invalid"/></data>""")
        val before = xml.write(root)
        assertFalse(normalize(root))
        assertArrayEquals(before, xml.write(root))
    }

}
