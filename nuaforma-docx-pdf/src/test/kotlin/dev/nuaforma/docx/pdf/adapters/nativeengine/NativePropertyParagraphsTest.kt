package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativePropertyParagraphsTest {
    private val context = GenerationContext()
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val begin = "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText>DOCPROPERTY Title \\* MERGEFORMAT</w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>"
    private val end = "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r>"

    private fun text(value: String) = "<w:r><w:t xml:space=\"preserve\">$value</w:t></w:r>"

    private fun fail(message: String): Nothing = throw IllegalArgumentException(message)

    private fun normalize(body: String, value: String): XmlElement {
        val root = ConversionXml(context).read("""<w:body xmlns:w="$word">$body</w:body>""".toByteArray())
        val values = NativeWordValues(context, mapOf("Title" to WordPackageMetadata.Property("lpwstr", value)), null, {}, ::fail)
        NativePropertyParagraphs(context, values, {}, { _, _ -> }, ::fail).normalize(root)
        NativeDocxFields.normalize(root, ::fail)
        return root
    }

    private fun source() = "<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr>${text("BEFORE ")}$begin${text("OLD ONE")}</w:p>" +
        "<w:p><w:pPr><w:jc w:val=\"left\"/></w:pPr>${text("OLD TWO")}</w:p>" +
        "<w:p><w:pPr><w:jc w:val=\"right\"/></w:pPr>$end${text(" AFTER")}</w:p>"

    @Test
    fun `actual property paragraphs retain prefix suffix and Word insertion formatting rather than stale cache`() {
        val root = normalize(source(), "First_x000d_\nSecond_x000d_\nThird_x000d_\nFourth")
        val paragraphs = root.elements()
        assertEquals(listOf("center", "center", null, "right"), paragraphs.map { p -> p.elements().singleOrNull { it.name.localPart == "pPr" }?.elements()?.single()?.attribute(word, "val") })
        assertEquals(listOf("BEFORE First", "Second", "Third", "Fourth AFTER"), paragraphs.map { p -> p.descendants().filter { it.name.localPart == "t" }.flatMap { it.children.filterIsInstance<XmlText>().asSequence() }.joinToString("") { it.text } })
        assertFalse(ConversionXml(context).write(root).decodeToString().contains("OLD"))
    }

    @Test
    fun `trailing paragraphs empty values and a single inline field preserve their ownership`() {
        assertEquals(3, normalize(source(), "First\r\nSecond\r\n").elements().size)
        assertEquals(1, normalize(source(), "").elements().size)
        assertEquals(2, normalize("<w:p>${text("BEFORE ")}<w:fldSimple w:instr=\"DOCPROPERTY Title\">${text("OLD")}</w:fldSimple>${text(" AFTER")}</w:p>", "One\rTwo").elements().size)
    }

    @Test
    fun `property decoding is single pass and rejects bare linefeeds forbidden controls and unpaired surrogates`() {
        val root = normalize(source(), "_x005F_x000D_")
        assertTrue(ConversionXml(context).write(root).decodeToString().contains("_x000D_"))
        for (value in listOf("One\nTwo", "_x0000_", "_xD800_", "x".repeat(16_385), "\r".repeat(64))) {
            assertThrows(IllegalArgumentException::class.java, { normalize(source(), value) }, value.take(40))
        }
    }

    @Test
    fun `locked malformed active nested and cross-section results reject rather than losing structures`() {
        for (bad in listOf(
            source().replace("w:fldCharType=\"begin\"", "w:fldCharType=\"begin\" w:fldLock=\"true\""),
            source().replace(text("OLD TWO"), "<w:r><w:drawing/></w:r>"),
            source().replace(text("OLD TWO"), "<w:hyperlink/>"),
            source().replace("<w:jc w:val=\"left\"/>", "<w:sectPr/>"),
            source().replace(end, ""),
            source().replace(text("OLD TWO"), begin + text("inner") + end))) {
            assertThrows(IllegalArgumentException::class.java, { normalize(bad, "First\rSecond") }, bad.take(80))
        }
    }

}
