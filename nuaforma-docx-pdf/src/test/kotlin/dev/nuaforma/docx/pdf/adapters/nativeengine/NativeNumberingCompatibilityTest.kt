package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeNumberingCompatibilityTest {
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private fun xml(text: String) = ConversionXml(GenerationContext()).read(text.toByteArray())

    private fun level(index: Int, format: String = "decimal", pattern: String = "%1.", extra: String = "") =
        """<w:lvl w:ilvl="$index"><w:start w:val="1"/><w:numFmt w:val="$format"/><w:lvlText w:val="$pattern"/>$extra</w:lvl>"""

    private fun root(extra: String = "", override: String = "", first: String = level(0)) = xml(
        """<w:numbering xmlns:w="$word"><w:abstractNum w:abstractNumId="0">$first</w:abstractNum><w:num w:numId="1"><w:abstractNumId w:val="0"/>$override</w:num>$extra</w:numbering>""")

    private fun paragraph(id: Int = 1, depth: Int = 0) = """<w:p><w:pPr><w:numPr><w:numId w:val="$id"/><w:ilvl w:val="$depth"/></w:numPr></w:pPr><w:r><w:t>Visible body</w:t></w:r></w:p>"""

    private fun body(text: String) = xml("""<w:body xmlns:w="$word">$text</w:body>""")

    @Test
    fun `table list items keep source order and frames still reject`() {
        val document = body(paragraph() + "<w:tbl><w:tr><w:tc>${paragraph()}</w:tc><w:tc>${paragraph()}</w:tc></w:tr></w:tbl>" + paragraph())
        val numbering = NativeDocxNumbering(root(), document) { emptyList() }
        assertEquals(listOf("1.", "2.", "3.", "4."), document.descendants().filter { it.name.localPart == "p" }.map { numbering.entry(it)!!.value.text }.toList())
        assertThrows(IllegalArgumentException::class.java) { NativeDocxNumbering(root(), body("<w:txbxContent>${paragraph()}</w:txbxContent>")) { emptyList() } }
    }

    @Test
    fun `empty overrides inherit and full modern overrides retain formatting and explicit start precedence`() {
        val p = body(paragraph())
        val empty = NativeDocxNumbering(root(override = "<w:lvlOverride w:ilvl=\"0\"/>"), p) { emptyList() }
        assertEquals("1.", empty.entry(p.elements().single())!!.value.text)
        val custom = level(0, pattern = "[%1]", extra = "<w:pPr><w:ind w:left=\"720\"/></w:pPr>")
        val override = "<w:lvlOverride w:ilvl=\"0\"><w:startOverride w:val=\"5\"/>$custom</w:lvlOverride>"
        val changed = NativeDocxNumbering(root(override = override), p) { emptyList() }.entry(p.elements().single())!!
        assertEquals("[5]", changed.value.text)
        assertEquals("720", changed.level.elements().single { it.name.localPart == "pPr" }.elements().single().attribute(word, "left"))
        assertThrows(IllegalArgumentException::class.java) { NativeDocxNumbering(root(override = override.replace("<w:lvl w:ilvl=\"0\"", "<w:lvl w:ilvl=\"1\"")), p) { emptyList() } }
    }

    @Test
    fun `known unused none and legacy definitions stay inert but active unsupported geometry and malformed metadata fail`() {
        for (definition in listOf(level(0, "none", ""), level(0, extra = "<w:legacy w:legacy=\"1\" w:legacySpace=\"0\" w:legacyIndent=\"283\"/>"))) {
            val extra = "<w:abstractNum w:abstractNumId=\"9\">$definition</w:abstractNum><w:num w:numId=\"9\"><w:abstractNumId w:val=\"9\"/></w:num>"
            val p = body(paragraph())
            assertEquals("1.", NativeDocxNumbering(root(extra), p) { emptyList() }.entry(p.elements().single())!!.value.text)
            assertThrows(IllegalArgumentException::class.java) { NativeDocxNumbering(root(extra), body(paragraph(9))) { emptyList() } }
            assertThrows(IllegalArgumentException::class.java) { NativeDocxNumbering(root(extra.replace("<w:lvl ", "<w:lvl unexpected=\"1\" ")), p) { emptyList() } }
        }
        val bad = level(0, "unknown", "%1")
        assertThrows(IllegalArgumentException::class.java) { NativeDocxNumbering(root("<w:abstractNum w:abstractNumId=\"9\">$bad</w:abstractNum>"), body(paragraph())) { emptyList() } }
    }

    @Test
    fun `resolver failures identify the first failing paragraph rather than the last style or later step`() {
        val p = body(paragraph() + paragraph() + paragraph() + paragraph())
        var focused: XmlElement? = null
        val failure = assertThrows(IllegalArgumentException::class.java) {
            NativeDocxNumbering(root(first = level(0, "upperRoman").replace("w:val=\"1\"", "w:val=\"3998\"")), p, focus = { focused = it }) { emptyList() }
        }
        assertTrue(failure.message!!.contains("Roman"))
        assertSame(p.elements()[2], focused)
        val inherited = body("<w:p><w:r><w:t>Inherited list</w:t></w:r></w:p>")
        assertThrows(IllegalArgumentException::class.java) {
            NativeDocxNumbering(root(), inherited, focus = { focused = it }) { _ ->
                focused = root()
                listOf(xml("""<w:style xmlns:w="$word"><w:pPr><w:numPr><w:numId w:val="99"/></w:numPr></w:pPr></w:style>"""))
            }
        }
        assertSame(inherited.elements().first(), focused)
    }

}
