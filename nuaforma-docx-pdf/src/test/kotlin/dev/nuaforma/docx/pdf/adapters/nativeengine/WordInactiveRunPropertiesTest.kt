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
    fun `horizontal scale retains bounded percentage values independently of inactive toggles`() {
        for (value in listOf(1, 50, 80, 100, 125, 600)) {
            val input = root("""<w:w w:val="$value"/>""")
            WordInactiveRunProperties.validateSource(input, ::reject)
            assertEquals(value / 100f, WordInactiveRunProperties.horizontalScale(input.elements().single(), ::reject))
        }
        for (value in listOf("0", "601", "100000", "NaN"))
            assertThrows(NativeDocxReadException::class.java) { WordInactiveRunProperties.validateSource(root("""<w:w w:val="$value"/>"""), ::reject) }
    }

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
    fun `zero positioning does not affect numbering text but other shifts are preserved`() {
        for (value in listOf("0", "+000", "-0")) {
            val input = root("""<w:position w:val="$value"/><w:w w:val="100"/>""")
            assertTrue(WordInactiveRunProperties.normalizeSource(input, ::reject))
            assertEquals(listOf("w"), input.elements().map { it.name.localPart })
        }
        val none = root("""<w:em w:val="none"/><w:position w:val="0"/>""")
        assertTrue(WordInactiveRunProperties.normalizeSource(none, ::reject))
        assertTrue(none.elements().isEmpty())
        val visible = root("""<w:em w:val="dot"/>""")
        assertFalse(WordInactiveRunProperties.normalizeSource(visible, ::reject))
        assertEquals("dot", visible.elements().single().attribute(word, "val"))
        val active = root("""<w:position w:val="-2"/>""")
        assertFalse(WordInactiveRunProperties.normalizeSource(active, ::reject))
        assertEquals("-2", active.elements().single().attribute(word, "val"))
        assertThrows(NativeDocxReadException::class.java) { WordInactiveRunProperties.normalizeSource(root("<w:position/>"), ::reject) }
    }

    @Test
    fun `original invalid namespaces duplicate attributes values and nested content reject`() {
        for (content in listOf("""<w:dstrike w:val="unknown"/>""", """<w:dstrike val="0"/>""",
            """<w:dstrike w:val="0" extra="1"/>""", """<x:dstrike w:val="0"/>""",
            """<w:dstrike w:val="0">text</w:dstrike>""", """<w:dstrike w:val="0"><w:b/></w:dstrike>""",
            """<w:dstrike w:val="0"/><w:dstrike w:val="0"/>""", "<w:w/>", """<w:w w:val="100.0"/>""",
            """<w:w w:val="-100"/>""", """<w:w w:val="1000000"/>""", """<w:w w:val="100"/><w:w w:val="100"/>""",
            "<w:em/>", """<w:em w:val="invalid"/>""", """<w:em w:val="none"><w:b/></w:em>""",
            """<w:em w:val="none"/><w:em w:val="none"/>""")) {
            assertThrows(NativeDocxReadException::class.java) { WordInactiveRunProperties.validateSource(root(content), ::reject) }
        }
    }

}
