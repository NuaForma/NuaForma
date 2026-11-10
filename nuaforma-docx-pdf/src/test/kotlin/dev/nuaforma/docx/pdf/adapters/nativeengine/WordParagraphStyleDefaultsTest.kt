package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import javax.xml.namespace.QName

class WordParagraphStyleDefaultsTest {
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val context = GenerationContext()
    private val resolver = WordParagraphStyleDefaults(context)

    private fun root(content: String) = ConversionXml(context).read(
        """<w:styles xmlns:w="$word">$content</w:styles>""".toByteArray())

    private fun select(content: String): String? {
        val tree = root(content)
        resolver.validateSource(tree) { _, message -> NativeDocxReadException(message, "word/styles.xml") }
        return resolver.select(tree.elements()) { _, message -> throw NativeDocxReadException(message, "word/styles.xml") }
            ?.attribute(word, "styleId")
    }

    @Test
    fun `explicit default takes priority regardless of style name or order`() {
        for (flag in listOf("1", "true", "on")) assertEquals("Chosen", select("""
            <w:style w:type="paragraph" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
            <w:style w:type="paragraph" w:styleId="Chosen" w:default="$flag"><w:name w:val="Other"/></w:style>
        """))
    }

    @Test
    fun `implicit fallback uses display name rather than ID or declaration order`() {
        for (flag in listOf("", " w:default=\"0\"", " w:default=\"false\"", " w:default=\"off\"")) {
            assertEquals("Chosen", select("""
                <w:style w:type="paragraph" w:styleId="Normal"><w:name w:val="Other"/></w:style>
                <w:style w:type="paragraph" w:styleId="Chosen"$flag><w:name w:val=" normal "/></w:style>
            """))
        }
        assertNull(select("""<w:style w:type="paragraph" w:styleId="Normal"><w:name w:val="Other"/></w:style>"""))
        assertNull(select("""<w:style w:type="paragraph" w:styleId="Unrelated"><w:name w:val="Unrelated"/></w:style>"""))
        assertNull(select(""))
    }

    @Test
    fun `ambiguous implicit definitions and unqualified aliases reject`() {
        val style = """<w:style w:type="paragraph" w:styleId="X"><w:name w:val="Normal"/></w:style>"""
        for (content in listOf(style + style.replace("styleId=\"X\"", "styleId=\"Y\""),
            style.replace("paragraph", "character"), style.replace("<w:name", "<f:name xmlns:f=\"urn:foreign\""),
            """<w:style w:type="paragraph" w:styleId="Normal"/>""",
            """<w:style w:type="paragraph" w:styleId="X"><w:name w:val="X"/><w:aliases w:val="Normal"/></w:style>""")) {
            assertThrows(NativeDocxReadException::class.java, { select(content) }, content)
        }
    }

    @Test
    fun `invalid original flags identify only offending attributes for cleanup`() {
        val tree = root("""<w:style w:type="paragraph" w:styleId="X" default="true"><w:name w:val="Normal"/></w:style>""")
        val failure = assertThrows(NativeDocxReadException::class.java) {
            resolver.validateSource(tree) { _, message -> NativeDocxReadException(message, "word/styles.xml") }
        }
        assertEquals(setOf(QName("", "default")), failure.unsupportedAttributes)
        for (flag in listOf("yes", "TRUE", "2", "")) assertThrows(NativeDocxReadException::class.java) {
            select("""<w:style w:type="paragraph" w:styleId="X" w:default="$flag"/>""")
        }
    }

    @Test
    fun `Word name boundaries distinguish trimming spaces from literal special spaces`() {
        for (space in listOf(" ", "\t", "\u00a0", "\u3000")) assertEquals("Chosen", select("""<w:style w:type="paragraph" w:styleId="Chosen"><w:name w:val="${space}Normal${space}"/></w:style>"""))
        for (space in listOf("\u2002", "\u202f")) assertNull(select("""<w:style w:type="paragraph" w:styleId="Normal"><w:name w:val="${space}Normal${space}"/></w:style>"""))
        val unsupported = "\u2003"
        assertThrows(NativeDocxReadException::class.java) { select("""<w:style w:type="paragraph" w:styleId="Normal"><w:name w:val="${unsupported}Normal${unsupported}"/></w:style>""") }
    }

}
