package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.PdfFixture
import dev.nuaforma.pdf.PdfParagraph
import dev.nuaforma.pdf.PdfTextRun
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class InheritedRunLanguageTest {

    private fun language(direct: String, style: String = "", default: String = "en-GB"): String? {
        val parts = PdfFixture.parts()
        parts["word/styles.xml"] = parts.getValue("word/styles.xml").decodeToString()
            .replaceFirst("<w:rPr>", "<w:rPr><w:lang w:val=\"$default\"/>")
            .replace("</w:style>", "<w:rPr>$style</w:rPr></w:style>").toByteArray()
        parts["word/document.xml"] = parts.getValue("word/document.xml").decodeToString()
            .replace("<w:rPr>", "<w:rPr>$direct").toByteArray()
        val model = NativeDocxReader(GenerationContext()) { _, _, _ -> "regular" }.read(PdfFixture.zip(parts))
        return model.blocks.filterIsInstance<PdfParagraph>().first().runs.filterIsInstance<PdfTextRun>().first().style.languageTag
    }

    @Test
    fun `missing Latin language inherits defaults and style even after regional metadata is normalized`() {
        for (direct in listOf("<w:lang/>", "<w:lang w:eastAsia=\"fr-FR\"/>", "<w:lang w:bidi=\"ar-SA\" w:eastAsia=\"en-US\"/>")) {
            assertEquals("en-GB", language(direct))
            assertEquals("nl-NL", language(direct, "<w:lang w:val=\"nl-NL\"/>"))
        }
        assertEquals("en-GB", language("<w:lang/>", "<w:lang w:eastAsia=\"fr-FR\"/>"))
    }

    @Test
    fun `explicit Latin language continues to override and validate`() {
        assertEquals("ru-RU", language("<w:lang w:val=\"ru-RU\"/>", "<w:lang w:val=\"nl-NL\"/>"))
        for (bad in listOf("", "bad_tag", "a")) assertThrows(NativeDocxReadException::class.java) { language("<w:lang w:val=\"$bad\"/>") }
    }

    @Test
    fun `foreign nested and duplicate language declarations remain errors`() {
        for (bad in listOf("<w:lang val=\"en-GB\"/>", "<w:lang extra=\"1\"/>", "<w:lang><w:r/></w:lang>", "<w:lang>text</w:lang>",
            "<w:lang w:val=\"en-GB\"/><w:lang/>", "<x:lang xmlns:x=\"urn:other\"/>"))
            assertThrows(NativeDocxReadException::class.java, { language(bad) }, bad)
    }

}
