package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordPaperSourceTest {
    private val xml = ConversionXml(GenerationContext())
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private fun document(content: String) = xml.read(("<w:document xmlns:w=\"$word\" xmlns:x=\"urn:unqualified\"><w:body><w:sectPr>" + content + "</w:sectPr></w:body></w:document>").toByteArray())

    @Test
    fun `original tray codes are bounded and source validation is read only`() {
        for (attributes in listOf("", "w:first=\"0\"", "w:other=\"65535\"", "w:first=\"15\" w:other=\"256\"", "w:first=\"00015\"")) {
            val root = document("<w:paperSrc $attributes/>")
            val before = xml.write(root)
            WordSectionMetadata.validateSource(root) { _, message -> error(message) }
            assertArrayEquals(before, xml.write(root))
        }
    }

    @Test
    fun `malformed or duplicate original tray settings cannot disappear during serialization`() {
        for (content in listOf("<w:paperSrc w:first=\"65536\"/>", "<w:paperSrc w:other=\"-1\"/>",
            "<w:paperSrc w:first=\"99999999999\"/>", "<w:paperSrc w:other=\"1.5\"/>", "<w:paperSrc first=\"1\"/>",
            "<w:paperSrc w:extra=\"1\"/>", "<w:paperSrc/><w:paperSrc/>", "<x:paperSrc/>",
            "<w:paperSrc><w:b/></w:paperSrc>", "<w:paperSrc>text</w:paperSrc>")) {
            assertThrows(IllegalArgumentException::class.java) {
                WordSectionMetadata.validateSource(document(content)) { _, message -> throw IllegalArgumentException(message) }
            }
        }
    }

    @Test
    fun `normalization preserves all physical page and section properties`() {
        val page = "<w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar w:top=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:right=\"1440\"/><w:type w:val=\"nextPage\"/>"
        val control = document(page)
        val actual = document(page + "<w:paperSrc w:first=\"15\" w:other=\"15\"/>")
        WordSectionMetadata.normalizeForms(actual) { _, message -> error(message) }
        assertArrayEquals(xml.write(control), xml.write(actual))
    }
}
