package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeEditingSelectionModelTest {
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val start = """<w:bookmarkStart w:id="701" w:name="_GoBack"/>"""
    private val end = """<w:bookmarkEnd w:id="701"/>"""

    @Test
    fun `unused selection removes only its two markers across paragraphs and part-local IDs`() {
        val context = GenerationContext()
        val xml = ConversionXml(context)
        val body = xml.read("""<w:body xmlns:w="$word"><w:p>$start<w:r><w:t>Before</w:t></w:r></w:p><w:p><w:r><w:t>After</w:t></w:r>$end</w:p></w:body>""".toByteArray())
        val band = xml.read("""<w:hdr xmlns:w="$word"><w:p><w:bookmarkStart w:id="701" w:name="Target"/><w:r><w:t>Band</w:t></w:r>$end</w:p></w:hdr>""".toByteArray())
        val beforeBand = xml.write(band)
        NativeDocxBookmarks(context).normalizeEditing(listOf(body, band), {}) { error(it) }
        assertEquals(2, body.elements().size)
        assertEquals(listOf("Before", "After"), body.descendants().filter { it.name.localPart == "t" }.map { node -> node.children.filterIsInstance<XmlText>().joinToString("") { it.text } }.toList())
        assertFalse(body.descendants().any { it.name.localPart.startsWith("bookmark") })
        assertArrayEquals(beforeBand, xml.write(band))
    }

    @Test
    fun `referenced ranges and malformed duplicate or unbalanced selections are retained or rejected`() {
        val context = GenerationContext()
        val xml = ConversionXml(context)
        val selected = "<w:p>$start<w:r><w:t>Visible</w:t></w:r>$end</w:p>"
        for (reference in listOf("""<w:p><w:hyperlink w:anchor="_GoBack"><w:r><w:t>Link</w:t></w:r></w:hyperlink></w:p>""",
            """<w:p><w:fldSimple w:instr="REF _GoBack"><w:r><w:t>Visible</w:t></w:r></w:fldSimple></w:p>""")) {
            val root = xml.read("""<w:body xmlns:w="$word">$selected$reference</w:body>""".toByteArray())
            val before = xml.write(root)
            NativeDocxBookmarks(context).normalizeEditing(listOf(root), {}) { error(it) }
            assertArrayEquals(before, xml.write(root))
        }
        for (invalid in listOf(selected.replace(end, ""), selected.replace(end, "<w:bookmarkEnd w:id=\"702\"/>"), selected+selected)) {
            val root = xml.read("""<w:body xmlns:w="$word">$invalid</w:body>""".toByteArray())
            assertThrows(IllegalArgumentException::class.java) { NativeDocxBookmarks(context).normalizeEditing(listOf(root), {}) { throw IllegalArgumentException(it) } }
        }
    }
}
