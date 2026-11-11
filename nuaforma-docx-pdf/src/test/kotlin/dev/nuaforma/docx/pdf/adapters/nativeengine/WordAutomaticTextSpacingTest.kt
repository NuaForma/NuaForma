package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.PdfFixture
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordAutomaticTextSpacingTest {
    private val context = GenerationContext()
    private val word = PdfFixture.W

    private fun xml(value: String) = ConversionXml(context).read(value.toByteArray())

    private fun parts(flags: String, text: String = "Latin Ελληνικά Кириллица 123") = linkedMapOf(
        "word/styles.xml" to xml("""<w:styles xmlns:w="$word"><w:docDefaults><w:pPrDefault><w:pPr>$flags<w:jc w:val="left"/></w:pPr></w:pPrDefault></w:docDefaults></w:styles>"""),
        "word/document.xml" to xml("""<w:document xmlns:w="$word"><w:body><w:p><w:r><w:t>$text</w:t></w:r></w:p></w:body></w:document>"""))

    private fun normalize(parts: Map<String, XmlElement>, field: (XmlElement) -> String? = { null }) {
        WordAutomaticTextSpacing(context).normalize(parts, field) { part, _, message -> NativeDocxReadException(message, part) }
    }

    @Test
    fun `all defined switches are inert for Latin Greek Cyrillic and numbers while other properties remain`() {
        for (name in listOf("autoSpaceDE", "autoSpaceDN")) for (value in listOf("", " w:val=\"1\"", " w:val=\"true\"", " w:val=\"on\"", " w:val=\"0\"", " w:val=\"false\"", " w:val=\"off\"")) {
            val parts = parts("<w:$name$value/>")
            normalize(parts)
            assertEquals(listOf("jc"), parts.getValue("word/styles.xml").descendants().first { it.name.localPart == "pPr" }.elements().map { it.name.localPart })
        }
    }

    @Test
    fun `malformed duplicate nested nonboolean and foreign attributes remain errors`() {
        for (name in listOf("autoSpaceDE", "autoSpaceDN")) for (bad in listOf("<w:$name w:val=\"invalid\"/>",
            "<w:$name val=\"0\"/>", "<w:$name xmlns:x=\"urn:other\" x:val=\"0\"/>", "<w:$name w:val=\"0\" extra=\"1\"/>",
            "<w:$name/><w:$name w:val=\"0\"/>", "<w:$name>text</w:$name>", "<w:$name><w:r/></w:$name>")) {
            val failure = assertThrows(NativeDocxReadException::class.java, { normalize(parts(bad)) }, bad)
            assertEquals("word/styles.xml", failure.part)
        }
    }

    @Test
    fun `enabled spacing rejects East Asian fullwidth punctuation and supplementary text across all stories`() {
        val samples = listOf("Latin 中文 123", "カタカナ", "ひらがな", "한글", "ㄅ", "Ａ1", "ｦ", "。", "\uD840\uDC00", "\uD82C\uDC00", "\uD81C\uDC00")
        for ((part, root) in listOf("word/document.xml" to "document", "word/header1.xml" to "hdr", "word/footer1.xml" to "ftr", "word/footnotes.xml" to "footnotes", "word/endnotes.xml" to "endnotes")) {
            for (text in samples) {
                val parts = parts("<w:autoSpaceDE/><w:autoSpaceDN/>")
                parts[part] = xml("""<w:$root xmlns:w="$word"><w:p><w:r><w:t>$text</w:t></w:r></w:p></w:$root>""")
                assertThrows(NativeDocxReadException::class.java, { normalize(parts) }, "$part $text")
            }
        }
        for (value in listOf("0", "false", "off")) assertDoesNotThrow {
            normalize(parts("<w:autoSpaceDE w:val=\"$value\"/><w:autoSpaceDN w:val=\"$value\"/>", "中文 123"))
        }
    }

    @Test
    fun `potential field text is inspected without requiring cached CJK display text`() {
        val parts = parts("<w:autoSpaceDE/>")
        parts["word/document.xml"] = xml("""<w:document xmlns:w="$word"><w:body><w:p><w:fldSimple w:instr="DOCPROPERTY example"><w:r><w:t>Latin cache</w:t></w:r></w:fldSimple></w:p></w:body></w:document>""")
        assertThrows(NativeDocxReadException::class.java) { normalize(parts) { "中文" } }
        assertDoesNotThrow { normalize(parts) { "Latin value" } }
    }

    private fun read(flags: String, body: String? = null, extraProperty: Boolean = false) {
        val parts = PdfFixture.parts()
        parts["word/styles.xml"] = parts.getValue("word/styles.xml").decodeToString().replace("</w:style>", "<w:pPr>$flags</w:pPr></w:style>").toByteArray()
        if (body != null) parts["word/document.xml"] = parts.getValue("word/document.xml").decodeToString().replace("<w:p><w:r>", "<w:p>$body<w:r>").toByteArray()
        if (extraProperty) {
            parts["docProps/custom.xml"] = """<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/custom-properties" xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes"><property fmtid="{D5CDD505-2E9C-101B-9397-08002B2CF9AE}" pid="2" name="example"><vt:lpwstr>_x4E2D_</vt:lpwstr></property></Properties>""".toByteArray()
            parts["_rels/.rels"] = parts.getValue("_rels/.rels").decodeToString().replace("</Relationships>", """<Relationship Id="property" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/custom-properties" Target="docProps/custom.xml"/></Relationships>""").toByteArray()
            parts["[Content_Types].xml"] = parts.getValue("[Content_Types].xml").decodeToString().replace("</Types>", """<Override PartName="/docProps/custom.xml" ContentType="application/vnd.openxmlformats-officedocument.custom-properties+xml"/></Types>""").toByteArray()
        }
        NativeDocxReader(context) { _, _, _ -> "regular" }.read(PdfFixture.zip(parts))
    }

    @Test
    fun `source reader retains namespace placement checks and guards decoded document properties and SET values`() {
        assertDoesNotThrow { read("<w:autoSpaceDE/><w:autoSpaceDN/>") }
        for (bad in listOf("<x:autoSpaceDE xmlns:x=\"urn:unknown\"/>", "<w:rPr><w:autoSpaceDE/></w:rPr>"))
            assertThrows(NativeDocxReadException::class.java) { read(bad) }
        val field = """<w:fldSimple w:instr="DOCPROPERTY example"><w:r><w:t>Latin cache</w:t></w:r></w:fldSimple>"""
        val failure = assertThrows(NativeDocxReadException::class.java) { read("<w:autoSpaceDE/>", field, true) }
        assertTrue(failure.message.orEmpty().contains("East Asian character spacing"), failure.message)
        val assignment = """<w:fldSimple w:instr='SET example "中文"'><w:r><w:t>Latin cache</w:t></w:r></w:fldSimple>"""
        val setFailure = assertThrows(NativeDocxReadException::class.java) { read("<w:autoSpaceDN/>", assignment) }
        assertTrue(setFailure.message.orEmpty().contains("East Asian character spacing"), setFailure.message)
    }

}
