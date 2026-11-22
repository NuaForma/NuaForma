package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class WordInlineCellFrameTest {
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val xml = ConversionXml(GenerationContext())
    private val inherited = """<w:framePr w:hSpace="187" w:wrap="around" w:vAnchor="text" w:hAnchor="text" w:y="1"/>"""
    private val inline = """<w:framePr w:hSpace="0" w:wrap="auto" w:vAnchor="margin" w:yAlign="inline"/>
        <w:suppressOverlap w:val="0"/>"""

    private fun properties(content: String) = xml.read("""<w:pPr xmlns:w="$word">$content</w:pPr>""".toByteArray())

    private fun validate(chain: List<XmlElement>, direct: XmlElement?, bodyCell: Boolean = true) {
        WordInlineCellFrame.validate(chain, direct, bodyCell) { _, message -> throw IllegalArgumentException(message) }
    }

    @Test
    fun `an explicit inline body cell frame overrides its known inherited frame`() {
        val style = properties(inherited + "<w:suppressOverlap/>")
        val direct = properties(inline)
        assertDoesNotThrow { validate(listOf(direct), direct) }
        assertDoesNotThrow { validate(listOf(style, direct), direct) }
    }

    @Test
    fun `visible inherited geometry or nonzero clearance and active overlap remain explicit`() {
        val style = properties(inherited)
        assertThrows(IllegalArgumentException::class.java) { validate(listOf(style), null) }
        val direct = properties(inline)
        assertThrows(IllegalArgumentException::class.java) { validate(listOf(style, direct), direct, bodyCell = false) }
        val withClearance = properties(inline.replace("hSpace=\"0\"", "hSpace=\"100\""))
        assertThrows(IllegalArgumentException::class.java) { validate(listOf(style, withClearance), withClearance) }
        val overlap = properties(inline.replace("val=\"0\"", "val=\"1\""))
        val failure = assertThrows(IllegalArgumentException::class.java) { validate(listOf(style, overlap), overlap) }
        assertEquals("Inline cell frames require inactive overlap suppression", failure.message)
    }
}
