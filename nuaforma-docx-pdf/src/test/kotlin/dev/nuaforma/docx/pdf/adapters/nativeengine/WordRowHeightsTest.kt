package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.pdf.PdfTable
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordRowHeightsTest {

    private fun root(content: String) = ConversionXml(GenerationContext()).read(
        """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:x="urn:unknown"><w:trPr>$content</w:trPr></w:document>""".toByteArray())

    private fun read(content: String): Float? {
        val root = root(content)
        WordRowHeights.validateSource(root) { _, message -> throw NativeDocxReadException(message, "word/document.xml") }
        return WordRowHeights.read(root.elements().single().elements().single()) { _, message ->
            throw NativeDocxReadException(message, "word/document.xml")
        }
    }

    @Test
    fun `Word omitted height rules select minimum content height and explicit auto retains natural height`() {
        assertEquals(17f, read("""<w:trHeight w:val="340"/>"""))
        assertEquals(17f, read("""<w:trHeight w:val="340" w:hRule="atLeast"/>"""))
        assertNull(read("""<w:trHeight w:val="0"/>"""))
        assertNull(read("""<w:trHeight w:val="0" w:hRule="auto"/>"""))
        val exact = root("""<w:trHeight w:val="340" w:hRule="exact"/>""")
        WordRowHeights.validateSource(exact) { _, message -> fail(message) }
        assertThrows(NativeDocxReadException::class.java) { read("""<w:trHeight w:val="340" w:hRule="exact"/>""") }
    }

    @Test
    fun `original malformed heights namespaces duplicates active exact and nonzero auto reject`() {
        for (value in listOf("-1", "1.5", "invalid", "288001", "10000000")) {
            assertThrows(NativeDocxReadException::class.java) { read("""<w:trHeight w:val="$value"/>""") }
        }
        for (content in listOf("<w:trHeight/>", """<w:trHeight w:val="1" extra="1"/>""",
            """<w:trHeight w:val="1" w:hRule="bad"/>""", """<x:trHeight w:val="1"/>""",
            """<w:trHeight val="1"/>""", """<w:trHeight w:val="1"/><w:trHeight w:val="2"/>""",
            """<w:trHeight w:val="1">text</w:trHeight>""", """<w:trHeight w:val="1"><w:b/></w:trHeight>""",
            """<w:trHeight w:val="1" w:hRule="auto"/>""")) {
            assertThrows(NativeDocxReadException::class.java) { read(content) }
        }
    }

    @Test
    fun `DOCX content floors translate into physical model height including the cell frame`() {
        for (paper in listOf("a4", "letter")) for (prefix in listOf("", "implicit-")) {
            val bytes = requireNotNull(javaClass.getResourceAsStream("/minimum-row-heights/$paper/${prefix}source.docx")).use { it.readBytes() }
            val document = NativeDocxReader(GenerationContext()) { _, _, _ -> "regular" }.read(bytes)
            val tables = document.blocks.filterIsInstance<PdfTable>()
            assertEquals(2, tables.size)
            assertEquals(listOf(null, 15.5f, 45.5f, 65.5f, 45.5f, null, 15.5f), tables.first().rows.map { it.minimumHeight })
            assertEquals(25.5f, tables.last().rows.first().minimumHeight)
            assertTrue(tables.last().rows.drop(1).all { it.minimumHeight == 85.5f })
            assertEquals(1, tables.last().repeatedHeaderRows)
        }
    }

}
