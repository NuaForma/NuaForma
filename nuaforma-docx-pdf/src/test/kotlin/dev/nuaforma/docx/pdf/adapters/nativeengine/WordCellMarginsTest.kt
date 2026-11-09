package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.pdf.PdfMargins
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordCellMarginsTest {
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val xml = ConversionXml(GenerationContext())

    private fun root(content: String) = xml.read("""<w:tcPr xmlns:w="$word" xmlns:x="urn:other">$content</w:tcPr>""".toByteArray())

    private fun reject(node: XmlElement, message: String): Nothing = throw NativeDocxReadException(message, "word/document.xml")

    @Test
    fun `partial declarations inherit each physical edge while zero resets its inherited value`() {
        val base = PdfMargins(4f, 5f, 4f, 5f)
        val inherited = root("""<w:tcMar><w:top w:w="40" w:type="dxa"/><w:left w:w="200" w:type="dxa"/></w:tcMar>""")
        val direct = root("""<w:tcMar><w:bottom w:w="142" w:type="dxa"/><w:left w:w="0" w:type="dxa"/></w:tcMar>""")
        val nodes = listOf(inherited, direct).map { it.elements().single() }
        val before = nodes.map(xml::write)
        assertEquals(PdfMargins(2f, 5f, 7.1f, 0f), WordCellMargins.resolve(nodes, base, ::reject))
        nodes.zip(before).forEach { (node, bytes) -> assertArrayEquals(bytes, xml.write(node)) }
        assertEquals(base, WordCellMargins.resolve(emptyList(), base, ::reject))
        assertEquals(base, WordCellMargins.resolve(root("<w:tcMar/>").elements(), base, ::reject))
    }

    @Test
    fun `original malformed units duplicates bounds namespaces and content reject before binding`() {
        val edges = listOf("<w:bottom/>", """<w:bottom w:w="-1" w:type="dxa"/>""",
            """<w:bottom w:w="1e3" w:type="dxa"/>""", """<w:bottom w:w="288001" w:type="dxa"/>""",
            """<w:bottom w:w="1" w:type="pct"/>""", """<w:bottom w:w="1"/>""",
            """<w:bottom w:w="1" w:type="dxa" extra="1"/>""", """<x:bottom w:w="1" w:type="dxa"/>""",
            """<w:bottom w="1" w:type="dxa"/>""", """<w:start w:w="1" w:type="dxa"/>""",
            """<w:bottom w:w="1" w:type="dxa">text</w:bottom>""",
            """<w:bottom w:w="1" w:type="dxa"><w:b/></w:bottom>""",
            """<w:bottom w:w="1" w:type="dxa"/><w:bottom w:w="2" w:type="dxa"/>""")
        for (edge in edges) assertThrows(NativeDocxReadException::class.java) {
            WordCellMargins.validateSource(root("<w:tcMar>$edge</w:tcMar>"), ::reject)
        }
        for (content in listOf("<w:tcMar/><w:tcMar/>", "<x:tcMar/>", "<w:tcMar>text</w:tcMar>", "<w:tcMar extra=\"1\"/>")) {
            assertThrows(NativeDocxReadException::class.java) { WordCellMargins.validateSource(root(content), ::reject) }
        }
    }

    @Test
    fun `Word fractional twips truncate before binding with bounds checked before truncation`() {
        val input = root("""<w:tcMar><w:bottom w:w="142.8" w:type="dxa"/></w:tcMar>""")
        val base = PdfMargins(4f, 5f, 4f, 5f)
        assertEquals(PdfMargins(4f, 5f, 7.1f, 5f), WordCellMargins.resolve(input.elements(), base, ::reject))
        assertTrue(WordCellMargins.normalizeSource(input, ::reject))
        assertFalse(WordCellMargins.normalizeSource(input, ::reject))
        assertEquals("142", input.elements().single().elements().single().attribute(word, "w"))
        assertThrows(NativeDocxReadException::class.java) {
            WordCellMargins.validateSource(root("""<w:tcMar><w:bottom w:w="288000.1" w:type="dxa"/></w:tcMar>"""), ::reject)
        }
    }

    @Test
    fun `nil zero restores table defaults past a cell style while physical zero resets the edge`() {
        val base = PdfMargins(4f, 5f, 7f, 10f)
        val style = root("""<w:tcMar><w:bottom w:w="600" w:type="dxa"/></w:tcMar>""")
        val input = root("""<w:tcMar><w:bottom w:w="0" w:type="nil"/><w:left w:w="0" w:type="dxa"/></w:tcMar>""")
        val nodes = style.elements() + input.elements()
        assertEquals(base.copy(left = 0f), WordCellMargins.resolve(nodes, base, ::reject))
        assertFalse(WordCellMargins.normalizeSource(input, ::reject))
        assertEquals(listOf("bottom", "left"), input.elements().single().elements().map { it.name.localPart })
        assertEquals(base.copy(left = 0f), WordCellMargins.resolve(nodes, base, ::reject))
        for (edge in listOf("""<w:bottom w:w="1" w:type="nil"/>""", """<w:bottom w:type="nil"/>""",
            """<w:bottom w:w="0.0" w:type="nil"/>""")) assertThrows(NativeDocxReadException::class.java) {
            WordCellMargins.validateSource(root("<w:tcMar>$edge</w:tcMar>"), ::reject)
        }
    }

}
