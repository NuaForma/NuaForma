package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.pdf.PdfColor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordRunBackgroundTest {
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val xml = ConversionXml(GenerationContext())

    private fun properties(content: String) = xml.read("""<w:rPr xmlns:w="$word" xmlns:x="urn:foreign">$content</w:rPr>""".toByteArray())

    private fun reject(node: XmlElement, message: String): Nothing = throw NativeDocxReadException(message, "word/document.xml")

    @Test
    fun `all named highlights and none retain exact source properties`() {
        val names = listOf("none", "black", "blue", "cyan", "green", "magenta", "red", "yellow", "white",
            "darkBlue", "darkCyan", "darkGreen", "darkMagenta", "darkRed", "darkYellow", "darkGray", "lightGray")
        for (name in names) {
            val root = properties("""<w:highlight w:val="$name"/>""")
            val before = xml.write(root)
            WordRunBackground.validateSource(root, GenerationContext(), ::reject)
            val result = WordRunBackground.highlight(root.elements().single(), ::reject)
            assertEquals(name == "none", result == null)
            assertArrayEquals(before, xml.write(root))
        }
        assertEquals(PdfColor(255, 255, 0), WordRunBackground.highlight(properties("""<w:highlight w:val="yellow"/>""").elements().single(), ::reject))
    }

    @Test
    fun `solid RGB auto nil and theme syntax are validated without rewriting source`() {
        val colors = WordThemeColors(null, ::reject)
        for ((attributes, expected) in listOf(
            """w:val="clear" w:fill="FFFFFF"""" to PdfColor(255, 255, 255),
            """w:val="clear" w:fill="008b90" w:color="FF0000"""" to PdfColor(0, 139, 144),
            """w:val="clear" w:fill="auto"""" to null,
            """w:val="nil"""" to null)) {
            val root = properties("<w:shd $attributes/>")
            val before = xml.write(root)
            WordRunBackground.validateSource(root, GenerationContext(), ::reject)
            assertEquals(expected, colors.runFill(root.elements().single()))
            assertArrayEquals(before, xml.write(root))
        }
        val theme = properties("""<w:shd w:val="clear" w:themeFill="accent1" w:themeFillTint="80" w:fill="000000"/>""")
        WordRunBackground.validateSource(theme, GenerationContext(), ::reject)
        assertThrows(NativeDocxReadException::class.java) { colors.runFill(theme.elements().single()) }
    }

    @Test
    fun `malformed duplicate foreign and active patterned backgrounds reject before binding`() {
        val invalid = listOf(
            "<w:highlight/>", """<w:highlight w:val="beige"/>""", """<w:highlight val="yellow"/>""",
            """<x:highlight w:val="yellow"/>""", """<w:highlight w:val="yellow" x:val="red"/>""",
            """<w:highlight w:val="yellow"><w:b/></w:highlight>""", """<w:highlight w:val="yellow">text</w:highlight>""",
            """<w:highlight w:val="yellow"/><w:highlight w:val="none"/>""",
            "<w:shd/>", """<w:shd w:val="solid" w:fill="FFFFFF"/>""", """<w:shd w:val="clear" w:fill="broken"/>""",
            """<x:shd w:val="clear"/>""", """<w:shd w:val="clear" fill="FFFFFF"/>""",
            """<w:shd w:val="clear"/><w:shd w:val="nil"/>""", """<w:shd w:val="nil" w:fill="broken"/>""",
            """<w:shd w:val="clear" w:themeFill="accent9"/>""", """<w:shd w:val="clear" w:themeFillTint="80"/>""",
            """<w:shd w:val="clear" w:themeFill="accent1" w:themeFillShade="XYZ"/>""",
            """<w:shd w:val="clear" w:color="not-a-color"/>""", """<w:shd w:val="clear">text</w:shd>""")
        for (content in invalid) assertThrows(NativeDocxReadException::class.java) {
            WordRunBackground.validateSource(properties(content), GenerationContext(), ::reject)
        }
    }

    @Test
    fun `automatic foreground follows Word dark and component threshold controls`() {
        val white = PdfColor(255, 255, 255)
        val black = PdfColor(0, 0, 0)
        for (color in listOf(PdfColor(0, 0, 0), PdfColor(59, 59, 59), PdfColor(199, 0, 0),
            PdfColor(0, 101, 0), PdfColor(0, 0, 255), PdfColor(10, 47, 65),
            PdfColor(1, 78, 120), PdfColor(4, 99, 4), PdfColor(75, 57, 34), PdfColor(12, 95, 3))) assertEquals(white, WordRunBackground.automaticForeground(color))
        for (color in listOf(null, PdfColor(60, 60, 60), PdfColor(200, 0, 0),
            PdfColor(0, 102, 0), PdfColor(0, 139, 144), PdfColor(2, 94, 35),
            PdfColor(3, 63, 192), PdfColor(74, 36, 149), PdfColor(75, 57, 35))) assertEquals(black, WordRunBackground.automaticForeground(color))
    }

}
