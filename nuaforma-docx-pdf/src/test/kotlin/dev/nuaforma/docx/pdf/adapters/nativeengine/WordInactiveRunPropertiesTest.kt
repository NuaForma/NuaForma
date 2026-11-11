package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordInactiveRunPropertiesTest {
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val xml = ConversionXml(GenerationContext())

    private fun root(content: String) = xml.read("""<w:rPr xmlns:w="$word" xmlns:x="urn:other">$content</w:rPr>""".toByteArray())

    private fun reject(node: XmlElement, message: String): Nothing = throw NativeDocxReadException(message, "word/styles.xml")

    @Test
    fun `inactive toggles and normal scale remain explicit while on off aliases normalize before binding`() {
        for (value in listOf("0", "false", "off")) {
            val input = root("""<w:dstrike w:val="$value"/><w:w w:val="100"/>""")
            WordInactiveRunProperties.validateSource(input, ::reject)
            input.elements().forEach { WordInactiveRunProperties.requireInactive(it, ::reject) }
            assertEquals(value == "off", WordInactiveRunProperties.normalizeSource(input, ::reject))
            assertFalse(WordInactiveRunProperties.normalizeSource(input, ::reject))
            input.elements().forEach { WordInactiveRunProperties.requireInactive(it, ::reject) }
        }
        val active = root("""<w:dstrike w:val="on"/><w:w w:val="105"/>""")
        WordInactiveRunProperties.validateSource(active, ::reject)
        assertTrue(WordInactiveRunProperties.normalizeSource(active, ::reject))
        for (node in active.elements()) assertThrows(NativeDocxReadException::class.java) { WordInactiveRunProperties.requireInactive(node, ::reject) }
    }

    @Test
    fun `original invalid namespaces duplicate attributes values and nested content reject`() {
        for (content in listOf("""<w:dstrike w:val="unknown"/>""", """<w:dstrike val="0"/>""",
            """<w:dstrike w:val="0" extra="1"/>""", """<x:dstrike w:val="0"/>""",
            """<w:dstrike w:val="0">text</w:dstrike>""", """<w:dstrike w:val="0"><w:b/></w:dstrike>""",
            """<w:dstrike w:val="0"/><w:dstrike w:val="0"/>""", "<w:w/>", """<w:w w:val="100.0"/>""",
            """<w:w w:val="-100"/>""", """<w:w w:val="1000000"/>""", """<w:w w:val="100"/><w:w w:val="100"/>""")) {
            assertThrows(NativeDocxReadException::class.java) { WordInactiveRunProperties.validateSource(root(content), ::reject) }
        }
    }

}
