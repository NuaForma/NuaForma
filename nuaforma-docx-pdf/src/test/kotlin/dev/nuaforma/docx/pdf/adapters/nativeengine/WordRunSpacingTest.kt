package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordRunSpacingTest {
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val xml = ConversionXml(GenerationContext())

    private fun root(content: String) = xml.read("""<w:rPr xmlns:w="$word" xmlns:x="urn:unknown">$content</w:rPr>""".toByteArray())

    private fun reject(node: XmlElement, message: String): Nothing = throw NativeDocxReadException(message, "word/document.xml")

    @Test
    fun `positive negative and explicit zero adjustments use twentieths of a point`() {
        for ((value, expected) in listOf("20" to 1f, "+2" to .1f, "-2" to -.1f, "0" to 0f, "-0" to 0f,
            "288000" to 14_400f, "-288000" to -14_400f)) {
            val node = root("""<w:spacing w:val="$value"/>""")
            val before = xml.write(node)
            WordRunSpacing.validateSource(node, ::reject)
            assertEquals(expected, WordRunSpacing.read(node.elements().single(), ::reject))
            assertArrayEquals(before, xml.write(node))
        }
    }

    @Test
    fun `original malformed attributes duplicates namespaces and invalid measurements reject`() {
        val invalid = listOf("<w:spacing/>", """<w:spacing w:val="1.5"/>""", """<w:spacing w:val="288001"/>""",
            """<w:spacing w:val="-288001"/>""", """<w:spacing w:val="1e3"/>""", """<w:spacing val="1"/>""",
            """<w:spacing w:val="1" extra="1"/>""", """<x:spacing w:val="1"/>""", """<w:spacing w:val="1">text</w:spacing>""",
            """<w:spacing w:val="1"><w:b/></w:spacing>""", """<w:spacing w:val="1"/><w:spacing w:val="2"/>""")
        for (content in invalid) assertThrows(NativeDocxReadException::class.java) { WordRunSpacing.validateSource(root(content), ::reject) }
    }

}
