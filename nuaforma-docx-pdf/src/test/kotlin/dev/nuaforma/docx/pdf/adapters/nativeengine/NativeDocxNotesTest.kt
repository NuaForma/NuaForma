package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.pdf.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeDocxNotesTest {
    private val namespace = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private fun fail(message: String): Nothing = throw IllegalArgumentException(message)
    private fun xml(text: String) = ConversionXml(GenerationContext()).read(text.toByteArray())
    private fun properties(text: String, kind: PdfNoteKind = PdfNoteKind.FOOTNOTE) =
        NativeDocxNotes.properties(xml("<w:pr xmlns:w=\"$namespace\">$text</w:pr>"), kind, NativeDocxNotes.defaults().getValue(kind), ::fail)
    private fun notes(content: String, policy: NativeDocxNotes.Numbering = NativeDocxNotes.defaults().getValue(PdfNoteKind.FOOTNOTE)) =
        NativeDocxNotes(mapOf(PdfNoteKind.FOOTNOTE to xml("<w:footnotes xmlns:w=\"$namespace\">$content</w:footnotes>")), mapOf(PdfNoteKind.FOOTNOTE to policy), ::fail)
    private val style = PdfTextStyle("regular", 9f)

    @Test fun `source order determines decimal roman and alphabetic note labels independently of source IDs`() {
        for ((format, expected) in listOf("decimal" to listOf("3", "4"), "lowerRoman" to listOf("iii", "iv"),
            "upperRoman" to listOf("III", "IV"), "lowerLetter" to listOf("c", "d"), "upperLetter" to listOf("C", "D"))) {
            val policy = properties("<w:numFmt w:val=\"$format\"/><w:numStart w:val=\"3\"/><w:numRestart w:val=\"continuous\"/>")
            val source = notes("<w:footnote w:id=\"8\"><w:p/></w:footnote><w:footnote w:id=\"0\"><w:p/></w:footnote>", policy)
            assertEquals("source-footnote0", source.call(PdfNoteKind.FOOTNOTE, "0", style, null).noteId)
            source.call(PdfNoteKind.FOOTNOTE, "8", style, null)
            val values = source.readDefinitions { _, _ -> listOf(PdfParagraph(listOf(PdfNoteLabel(style), PdfTextRun(" Note", style)))) }
            assertEquals(expected, values.map { it.number.text })
        }
        assertEquals(PdfNumberFormat.LOWER_ROMAN, NativeDocxNotes.defaults().getValue(PdfNoteKind.ENDNOTE).format)
        assertEquals(1, properties("<w:pos w:val=\"docEnd\"/>", PdfNoteKind.ENDNOTE).start)
        assertEquals(1, properties("<w:footnote w:id=\"-1\"/><w:footnote w:id=\"0\"/>").start)
    }

    @Test fun `invalid policies and changed separator geometry are rejected rather than silently normalized`() {
        for (property in listOf("<w:numFmt w:val=\"chicago\"/>", "<w:numStart w:val=\"0\"/>",
            "<w:numRestart w:val=\"eachSect\"/>", "<w:pos w:val=\"beneathText\"/>",
            "<w:numStart w:val=\"1\"/><w:numStart w:val=\"2\"/>", "<w:unknown w:val=\"1\"/>",
            "<w:numFmt/>", "<w:numFmt w:val=\"decimal\"><w:numStart/></w:numFmt>", "<w:footnote/>",
            "<w:footnote w:id=\"bad\"/>")) assertThrows(IllegalArgumentException::class.java, { properties(property) }, property)
        for (content in listOf("<w:footnote w:id=\"-2\"><w:p/></w:footnote>",
            "<w:footnote w:id=\"1\"/><w:footnote w:id=\"1\"/>", "<w:footnote w:id=\"bad\"/>",
            "<w:footnote w:type=\"unknown\" w:id=\"-1\"/>",
            "<w:footnote w:type=\"separator\" w:id=\"-1\"><w:p><w:r><w:t>Visible</w:t></w:r></w:p></w:footnote>",
            "<w:footnote w:type=\"separator\" w:id=\"-1\"><w:p><w:pPr><w:spacing w:after=\"120\"/></w:pPr><w:r><w:separator/></w:r></w:p></w:footnote>",
            "<w:footnote w:type=\"continuationNotice\" w:id=\"-1\"><w:p><w:r><w:t>Continued</w:t></w:r></w:p></w:footnote>"))
            assertThrows(IllegalArgumentException::class.java, { notes(content) }, content)
        val source = notes("<w:footnote w:id=\"1\"><w:p/></w:footnote>")
        assertThrows(IllegalArgumentException::class.java) { source.call(PdfNoteKind.FOOTNOTE, "2", style, null) }
        source.call(PdfNoteKind.FOOTNOTE, "1", style, null)
        assertThrows(IllegalArgumentException::class.java) { source.call(PdfNoteKind.FOOTNOTE, "1", style, null) }
        assertThrows(IllegalArgumentException::class.java) {
            notes("<w:footnote w:id=\"1\"><w:p/></w:footnote>", properties("<w:footnote w:id=\"1\"/>"))
        }
        assertThrows(IllegalArgumentException::class.java) { properties("<w:footnote w:id=\"-1\"/><w:footnote w:id=\"-1\"/>") }
        val separator = "<w:footnote w:type=\"separator\" w:id=\"-1\"><w:p><w:r><w:separator/></w:r></w:p></w:footnote>"
        assertNotNull(notes(separator, properties("<w:footnote w:id=\"-1\"/>")))
    }
}
