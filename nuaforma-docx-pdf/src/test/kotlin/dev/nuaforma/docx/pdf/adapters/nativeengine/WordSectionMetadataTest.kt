package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordSectionMetadataTest {
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val xml = ConversionXml(GenerationContext())

    private fun source(content: String) = xml.read("<w:sectPr xmlns:w=\"$word\" xmlns:x=\"urn:unknown\">$content<w:pgSz w:w=\"11906\" w:h=\"16838\"/></w:sectPr>".toByteArray())

    private fun rejected(content: String) = assertThrows(NativeDocxReadException::class.java) {
        WordSectionMetadata.validateSource(source(content)) { _, message -> throw NativeDocxReadException(message, "word/document.xml") }
    }

    @Test
    fun `original valid section settings remain byte equivalent before serialization`() {
        for (start in listOf("nextPage", "continuous", "oddPage", "evenPage", "nextColumn")) {
            val root = source("<w:type w:val=\"$start\"/><w:formProt w:val=\"true\"/>")
            val original = xml.write(root)
            WordSectionMetadata.validateSource(root) { _, message -> throw NativeDocxReadException(message, "word/document.xml") }
            assertArrayEquals(original, xml.write(root))
        }
    }

    @Test
    fun `every qualified protection spelling is inert in the private render view`() {
        for (value in listOf(null, "0", "1", "false", "true", "off", "on")) {
            val root = source("<w:type w:val=\"continuous\"/><w:formProt${value?.let { " w:val=\"$it\"" }.orEmpty()}/>")
            WordSectionMetadata.normalizeForms(root) { _, message -> throw NativeDocxReadException(message, "word/document.xml") }
            assertEquals(listOf("type", "pgSz"), root.elements().map { it.name.localPart })
            assertEquals("continuous", root.elements().first().attribute(word, "val"))
            assertEquals("11906", root.elements().last().attribute(word, "w"))
        }
    }

    @Test
    fun `unknown malformed or duplicate protection settings reject`() {
        for (content in listOf("<x:formProt w:val=\"1\"/>", "<w:formProt w:val=\"yes\"/>", "<w:formProt w:val=\"TRUE\"/>", "<w:formProt x:val=\"1\"/>",
            "<w:formProt extra=\"1\"/>", "<w:formProt>text</w:formProt>", "<w:formProt><w:b/></w:formProt>",
            "<w:formProt/><w:formProt w:val=\"0\"/>")) rejected(content)
    }

    @Test
    fun `missing unknown or ambiguous section start metadata rejects`() {
        for (content in listOf("<x:type w:val=\"continuous\"/>", "<w:type/>", "<w:type w:val=\"continuous\" extra=\"1\"/>", "<w:type x:val=\"continuous\"/>",
            "<w:type w:val=\"unknown\"/>", "<w:type w:val=\"continuous\">text</w:type>",
            "<w:type w:val=\"continuous\"/><w:type w:val=\"nextPage\"/>")) rejected(content)
    }
}
