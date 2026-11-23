package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WordInactiveDiagonalBordersTest {

    @Test
    fun `inert diagonals keep physical edges and remove an otherwise empty border container`() {
        val inactive = """<w:tl2br w:val="none" w:sz="0" w:space="0" w:color="auto"/><w:tr2bl w:val="nil"/>"""
        val left = """<w:left w:val="single" w:sz="4" w:color="223344"/>"""
        val explicit = root(inactive + left)
        assertTrue(WordTableBorders.removeInactiveDiagonals(explicit))
        assertEquals("left", explicit.elements().single().elements().single().name.localPart)
        assertFalse(WordTableBorders.removeInactiveDiagonals(explicit))
        val empty = root(inactive)
        assertTrue(WordTableBorders.removeInactiveDiagonals(empty))
        assertTrue(empty.elements().isEmpty())
    }

    @Test
    fun `visible and unqualified diagonal declarations remain for the ordinary profile diagnostic`() {
        for (edge in listOf(
            """<w:tl2br w:val="single" w:sz="4" w:color="223344"/>""",
            """<w:tr2bl w:val="none" w:space="2"/>""",
            """<w:tr2bl w:val="nil" w:themeColor="accent1" w:themeTint="80"/>""",
        )) {
            val source = root(edge)
            assertFalse(WordTableBorders.removeInactiveDiagonals(source))
            assertEquals(1, source.elements().single().elements().size)
        }
    }

    private fun root(content: String): XmlElement = ConversionXml(GenerationContext()).read(
        """<w:tcPr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:tcBorders>$content</w:tcBorders></w:tcPr>""".toByteArray(),
    ).also { root ->
        WordTableBorders.validateSource(root) { _, explanation -> throw IllegalArgumentException(explanation) }
    }
}
