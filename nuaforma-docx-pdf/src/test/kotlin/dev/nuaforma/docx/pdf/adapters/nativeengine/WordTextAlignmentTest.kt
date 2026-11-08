package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.PdfFixture
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordTextAlignmentTest {
    private val word = PdfFixture.W

    private fun normalize(flags: String, text: String = "Latin Ελληνικά Кириллица 123", field: String? = null, story: String = "document") {
        val context = GenerationContext()
        val xml = ConversionXml(context)
        val parts = mapOf("word/story.xml" to xml.read("""<w:$story xmlns:w="$word"><w:p><w:pPr>$flags</w:pPr><w:r><w:t>$text</w:t></w:r><w:fldSimple w:instr="DOCPROPERTY sample"><w:r><w:t>cache</w:t></w:r></w:fldSimple></w:p></w:$story>""".toByteArray()))
        WordAutomaticTextSpacing(context).normalize(parts, { field }) { part, _, message -> NativeDocxReadException(message, part) }
    }

    private fun source(flags: String, body: String = "") {
        val parts = PdfFixture.parts()
        parts["word/document.xml"] = parts.getValue("word/document.xml").decodeToString()
            .replace("<w:p><w:r>", "<w:p><w:pPr>$flags</w:pPr>$body<w:r>").toByteArray()
        NativeDocxReader(GenerationContext()) { _, _, _ -> "regular" }.read(PdfFixture.zip(parts))
    }

    @Test
    fun `baseline and bounded automatic alignment pass the actual source reader`() {
        for (value in listOf("auto", "baseline")) assertDoesNotThrow { source("<w:textAlignment w:val=\"$value\"/>") }
        assertDoesNotThrow { normalize("<w:textAlignment w:val=\"auto\"/>") }
        assertDoesNotThrow { normalize("<w:textAlignment w:val=\"baseline\"/>", "中文 العربية") }
    }

    @Test
    fun `unsupported positions malformed duplicate and misplaced properties remain errors`() {
        for (bad in listOf("<w:textAlignment/>", "<w:textAlignment w:val=\"top\"/>", "<w:textAlignment w:val=\"center\"/>",
            "<w:textAlignment w:val=\"bottom\"/>", "<w:textAlignment val=\"auto\"/>", "<w:textAlignment w:val=\"auto\" extra=\"1\"/>",
            "<w:textAlignment w:val=\"auto\"/><w:textAlignment w:val=\"baseline\"/>",
            "<w:textAlignment w:val=\"auto\">text</w:textAlignment>", "<w:textAlignment w:val=\"auto\"><w:r/></w:textAlignment>",
            "<x:textAlignment xmlns:x=\"urn:other\" w:val=\"auto\"/>", "<w:rPr><w:textAlignment w:val=\"auto\"/></w:rPr>"))
            assertThrows(NativeDocxReadException::class.java, { source(bad) }, bad)
    }

    @Test
    fun `automatic alignment guards other scripts fullwidth and potential field text`() {
        for (text in listOf("中文", "العربية", "עברית", "हिन्दी", "Ａ", "\uD840\uDC00")) {
            assertThrows(NativeDocxReadException::class.java) { normalize("<w:textAlignment w:val=\"auto\"/>", text) }
            assertThrows(NativeDocxReadException::class.java) { normalize("<w:textAlignment w:val=\"auto\"/>", field = text) }
        }
        for (story in listOf("hdr", "ftr", "footnotes", "endnotes"))
            assertThrows(NativeDocxReadException::class.java) { normalize("<w:textAlignment w:val=\"auto\"/>", "עברית", story = story) }
        assertThrows(NativeDocxReadException::class.java) {
            source("<w:textAlignment w:val=\"auto\"/>", "<w:r><w:rPr><w:rtl/></w:rPr><w:t>Latin</w:t></w:r>")
        }
        val assignment = """<w:fldSimple w:instr='SET sample "中文"'><w:r><w:t>Latin cache</w:t></w:r></w:fldSimple>"""
        val error = assertThrows(NativeDocxReadException::class.java) { source("<w:textAlignment w:val=\"auto\"/>", assignment) }
        assertTrue(error.message.orEmpty().contains("Automatic paragraph text alignment"), error.message)
    }

}
