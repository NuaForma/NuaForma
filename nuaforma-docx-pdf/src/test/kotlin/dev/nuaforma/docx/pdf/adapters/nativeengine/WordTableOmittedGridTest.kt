package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordTableOmittedGridTest {
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val xml = ConversionXml(GenerationContext())
    private val grid = listOf(50f, 60f, 70f)

    private fun row(data: String): XmlElement =
        xml.read("""<w:trPr xmlns:w="$word" xmlns:x="urn:foreign">$data</w:trPr>""".toByteArray())

    private fun reject(node: XmlElement, reason: String): Nothing =
        throw IllegalArgumentException(reason + " at " + node.name.localPart)

    @Test
    fun omittedEdgesUseTheOriginalPhysicalGrid() {
        val properties = row("""<w:gridBefore w:val="1"/><w:wBefore w:type="dxa" w:w="1000"/>
            <w:gridAfter w:val="1"/><w:wAfter w:type="dxa" w:w="1400"/>""")
        WordTableOmittedGrid.validateSource(properties, ::reject)
        assertEquals(1 to 1, WordTableOmittedGrid.read(properties, grid, ::reject))
        assertEquals(1 to 0, WordTableOmittedGrid.read(row("""<w:gridBefore w:val="1"/>"""), grid, ::reject))
        assertEquals(0 to 0, WordTableOmittedGrid.read(null, grid, ::reject))
    }

    @Test
    fun mismatchedWidthAndNonlocalDeclarationsArePreservedAsErrors() {
        val mismatched = row("""<w:gridAfter w:val="1"/><w:wAfter w:type="dxa" w:w="1300"/>""")
        val failure = assertThrows(IllegalArgumentException::class.java) { WordTableOmittedGrid.read(mismatched, grid, ::reject) }
        assertEquals("Trailing omission width differs from the declared table grid at wAfter", failure.message)
        for (markup in listOf("""<x:gridAfter w:val="1"/>""", """<w:gridBefore w:val="16"/>""",
            """<w:gridBefore w:val="1"/><w:gridBefore w:val="1"/>""",
            """<w:gridBefore w:val="1"/><w:wBefore w:type="pct" w:w="1000"/>""")) {
            assertThrows(IllegalArgumentException::class.java) { WordTableOmittedGrid.validateSource(row(markup), ::reject) }
        }
    }
}
