package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.pdf.PdfPreferredWidth
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordTableWidthSyntaxTest {
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val xml = ConversionXml(GenerationContext())

    private fun node(value: String, unit: String) = xml.read(
        """<w:tblW xmlns:w="$word" w:w="$value" w:type="$unit"/>""".toByteArray())

    private fun reject(node: XmlElement, message: String): Nothing = throw NativeDocxReadException(message, "word/document.xml")

    @Test
    fun `literal percentages and positive decimal twips preserve qualified Word preferences`() {
        assertEquals(PdfPreferredWidth.percent(100f), WordTableWidths.preferred(node("100%", "pct"), ::reject))
        assertEquals(PdfPreferredWidth.percent(33.3f), WordTableWidths.preferred(node("33.3%", "pct"), ::reject))
        assertEquals(PdfPreferredWidth.percent(33.3f), WordTableWidths.preferred(node("1665.0", "pct"), ::reject))
        assertEquals(PdfPreferredWidth.points(512f), WordTableWidths.preferred(node("10240.0", "dxa"), ::reject))
        assertEquals(PdfPreferredWidth.points(61.4f), WordTableWidths.preferred(node("1228.8", "dxa"), ::reject))
        assertNull(WordTableWidths.preferred(node("0.0", "auto"), ::reject))
        assertNull(WordTableWidths.preferred(node("0", "nil"), ::reject))
    }

    @Test
    fun `canonicalization is idempotent and retains the source caller bytes`() {
        val original = """<w:tblPr xmlns:w="$word"><w:tblW w:w="100%" w:type="pct"/></w:tblPr>""".toByteArray()
        val before = original.copyOf()
        val root = xml.read(original)
        assertTrue(WordTableWidths.normalizeSource(root, ::reject))
        assertEquals("5000", root.elements().single().attribute(word, "w"))
        assertEquals("pct", root.elements().single().attribute(word, "type"))
        assertFalse(WordTableWidths.normalizeSource(root, ::reject))
        assertArrayEquals(before, original)
    }

    @Test
    fun `malformed spellings unsupported precision and out of range values reject before normalization`() {
        for ((value, unit) in listOf("-1" to "dxa", "1e3" to "dxa", ".5" to "dxa", "288000.1" to "dxa",
            "1%" to "dxa", "100.000001%" to "pct", "0.01%" to "pct", "5001.0" to "pct", "0.1" to "auto")) {
            assertThrows(NativeDocxReadException::class.java) { WordTableWidths.preferred(node(value, unit), ::reject) }
        }
        for (content in listOf("""<w:tblW w:w="100%" w:type="pct" extra="1"/>""",
            """<w:tblW w:w="100%" w:type="pct"/><w:tblW w:w="5000" w:type="pct"/>""",
            """<w:tblW w:w="100%" w:type="pct">text</w:tblW>""")) {
            val root = xml.read("""<w:tblPr xmlns:w="$word">$content</w:tblPr>""".toByteArray())
            assertThrows(NativeDocxReadException::class.java) { WordTableWidths.normalizeSource(root, ::reject) }
        }
    }

}
