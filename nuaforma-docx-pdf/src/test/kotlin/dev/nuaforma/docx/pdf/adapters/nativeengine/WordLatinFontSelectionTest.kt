package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordLatinFontSelectionTest {

    private fun segments(text: String, context: GenerationContext = GenerationContext()) =
        WordLatinFontSelection.segments(text, context, { throw IllegalArgumentException(it) }, { require(it) { "Font segment budget" } })

    @Test
    fun `default hint selects independent ASCII and high ANSI character ranges without losing whitespace`() {
        val value = "ASCII é € ΩЖ – end\u00a0"
        val result = segments(value)
        assertEquals(value, result.joinToString("") { it.text })
        assertEquals(listOf("ASCII ", "é", " ", "€", " ", "ΩЖ", " ", "–", " end", "\u00a0"), result.map { it.text })
        assertEquals(listOf(false, true, false, true, false, true, false, true, false, true), result.map { it.highAnsi })
        assertTrue(segments("").isEmpty())
        assertEquals(listOf(WordLatinFontSelection.Segment("same ASCII", false)), segments("same ASCII"))
    }

    @Test
    fun `unqualified selectors and combining clusters fail instead of inventing font fallback`() {
        for (text in listOf("中", "ا", "א", "Ａ", "\u3000", "😀", "E\u0301", "\uD800")) {
            assertThrows(IllegalArgumentException::class.java) { segments(text) }
        }
    }

    @Test
    fun `font segmentation stays bounded at the existing paragraph run limit`() {
        assertEquals(1_000, segments("aé".repeat(500)).size)
        assertThrows(IllegalArgumentException::class.java) { segments("aé".repeat(501)) }
    }

    @Test
    fun `font selection cooperates with cancellation while examining source characters`() {
        var checks = 0
        val error = assertThrows(GenerationException::class.java) {
            segments("aé".repeat(100), GenerationContext(cancellation = CancellationToken { ++checks > 8 }))
        }
        assertEquals(DiagnosticCodes.CANCELLED, error.diagnostics.single().code)
    }

    private fun properties(content: String) = ConversionXml(GenerationContext()).read(
        ("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" xmlns:x=\"urn:unqualified\"><w:body><w:p><w:r><w:rPr>" +
            content + "</w:rPr><w:t>Visible text</w:t></w:r></w:p></w:body></w:document>").toByteArray())

    @Test
    fun `original font metadata is checked without qualifying script roles or rewriting valid source`() {
        for (role in listOf("majorAscii", "minorHAnsi", "majorEastAsia", "minorBidi")) {
            val root = properties("<w:rFonts w:ascii=\"Cache font\" w:asciiTheme=\"$role\" w:hint=\"default\"/>")
            val xml = ConversionXml(GenerationContext())
            val before = xml.write(root)
            WordLatinFontSelection.validateSource(root) { _, message -> error(message) }
            assertArrayEquals(before, xml.write(root))
        }
    }

    @Test
    fun `invalid original font attributes and duplicates cannot disappear during serialization`() {
        for (value in listOf("<w:rFonts extra=\"1\"/>", "<w:rFonts w:asciiTheme=\"unknown\"/>",
            "<w:rFonts/><w:rFonts/>", "<x:rFonts/>", "<w:rFonts w:hint=\"unknown\"/>", "<w:rFonts><w:b/></w:rFonts>")) {
            assertThrows(IllegalArgumentException::class.java) {
                WordLatinFontSelection.validateSource(properties(value)) { _, message -> throw IllegalArgumentException(message) }
            }
        }
    }

}
