package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordHeaderControlDirectionTest {
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val context = GenerationContext()
    private val xml = ConversionXml(context)
    private val properties = """
        <w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans" w:hint="cs"/>
        <w:sz w:val="22"/><w:szCs w:val="22"/><w:rtl/><w:lang w:bidi="ar-SA"/></w:rPr>
    """.trimIndent()

    private fun source(localName: String, content: String) =
        xml.read("""<w:$localName xmlns:w="$word">$content</w:$localName>""".toByteArray())

    private fun header(text: String = "Header text", leader: String = "none", style: String = properties) =
        source("hdr", """
            <w:p><w:pPr><w:tabs><w:tab w:val="right" w:pos="6400" w:leader="$leader"/></w:tabs>
            $style</w:pPr><w:r>$style<w:tab/></w:r><w:r><w:t>$text</w:t></w:r></w:p>
            """.trimIndent())

    private fun settings(active: Boolean = true) =
        source("settings", """<w:compat><w:useFELayout w:val="${if (active) "1" else "0"}"/></w:compat>""")

    private fun activeDirections(root: XmlElement) = root.descendants().count { it.name.localPart == "rtl" }

    @Test
    fun `matched header text metrics and a control-only direction can use the ordinary path`() {
        val header = header()
        val body = source("document", "<w:body><w:p><w:r><w:t>Ordinary body</w:t></w:r></w:p></w:body>")
        val sections = mapOf("word/header1.xml" to header, "word/document.xml" to body, "word/settings.xml" to settings())
        assertTrue(WordAuthoringMetadata.containsComplexText(sections.values))
        WordHeaderControlDirection.normalize(sections, context)
        assertEquals(0, activeDirections(header))
        assertFalse(WordAuthoringMetadata.containsComplexText(sections.values))
        val fonts = header.descendants().filter { it.name.localPart == "rFonts" }.toList()
        assertEquals(2, fonts.size)
        assertTrue(fonts.all { it.attribute(word, "hint") == null && it.attribute(word, "ascii") == "Liberation Sans" })
        assertEquals(2, header.descendants().count { it.name.localPart == "tab" })
    }

    @Test
    fun `an empty header paragraph with a qualified tab retains its layout controls`() {
        val header = header(" ")
        val parts = mapOf("word/header2.xml" to header, "word/settings.xml" to settings())
        WordHeaderControlDirection.normalize(parts, context)
        assertEquals(0, activeDirections(header))
        assertEquals(2, header.descendants().count { it.name.localPart == "tab" })
        assertEquals(2, header.descendants().count { it.name.localPart == "sz" })
    }

    @Test
    fun `substituted complex content and ambiguous directions retain original print settings`() {
        val ltr = "<w:body><w:p><w:r><w:t>Body</w:t></w:r></w:p></w:body>"
        val arabic = "<w:body><w:p><w:r><w:t>العربية</w:t></w:r></w:p></w:body>"
        val inherited = source("styles", "<w:style><w:pPr><w:bidi/></w:pPr></w:style>")
        val inheritedLeader = source("styles", """<w:style><w:pPr><w:tabs><w:tab w:leader="dot"/></w:tabs></w:pPr></w:style>""")
        for ((header, body, additional) in listOf(
            Triple(header(), source("document", arabic), emptyMap()),
            Triple(header("العربية"), source("document", ltr), emptyMap()),
            Triple(header(leader = "dot"), source("document", ltr), emptyMap()),
            Triple(header(style = properties.replace("""w:szCs w:val="22"""", """w:szCs w:val="24"""")), source("document", ltr), emptyMap()),
            Triple(header(), source("document", ltr), mapOf("word/styles.xml" to inherited)),
            Triple(header(), source("document", ltr), mapOf("word/styles.xml" to inheritedLeader)),
            Triple(header(), source("document", ltr), mapOf("word/settings.xml" to settings(active = false))),
        )) {
            val before = xml.write(header)
            WordHeaderControlDirection.normalize(mapOf(
                "word/header1.xml" to header, "word/document.xml" to body, "word/settings.xml" to settings()) + additional, context)
            assertArrayEquals(before, xml.write(header))
        }
    }
}
