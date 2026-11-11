package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import javax.xml.namespace.QName

class WordHorizontalSectionTest {
    private val context = GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))
    private val xml = ConversionXml(context)
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private fun root(direction: String): XmlElement = xml.read(("<w:document xmlns:w=\"$word\"><w:body>" +
        "<w:p><w:pPr><w:bidi/></w:pPr><w:r><w:t>visible</w:t></w:r></w:p>" +
        "<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/>" + direction + "</w:sectPr></w:body></w:document>").toByteArray())

    private fun validate(root: XmlElement) {
        val source = NativeDocxSource(context)
        source.index(root, "word/document.xml")
        WordSectionMetadata.validateSource(root) { node, message ->
            source.current = node
            throw source.failure(message, "word/document.xml")
        }
    }

    @Test
    fun `normalizing the default section direction preserves geometry paragraph bidi and unrelated data`() {
        val root = root("<w:textDirection w:val=\"lrTb\"/>")
        val paragraph = root.descendants().single { it.name == QName(word, "p") }
        val before = xml.write(paragraph)
        validate(root)
        WordSectionMetadata.normalizeForms(root) { _, message -> fail(message) }
        assertArrayEquals(before, xml.write(paragraph))
        assertTrue(root.descendants().none { it.name == QName(word, "textDirection") })
        assertEquals("12240", root.descendants().single { it.name == QName(word, "pgSz") }.attribute(word, "w"))
        assertTrue(root.descendants().any { it.name == QName(word, "bidi") })
    }

    @Test
    fun `unknown attributes namespaces payloads and active directions fail at the original element`() {
        for (direction in listOf("<w:textDirection w:val=\"tbRl\"/>", "<w:textDirection w:val=\"lrTbV\"/>",
            "<w:textDirection/>", "<textDirection xmlns=\"urn:foreign\" w:val=\"lrTb\"/>",
            "<w:textDirection w:val=\"lrTb\" foreign=\"1\"/>",
            "<w:textDirection w:val=\"lrTb\"><w:unknown/></w:textDirection>",
            "<w:textDirection w:val=\"lrTb\"/><w:textDirection w:val=\"lrTb\"/>")) {
            val failure = assertThrows(NativeDocxReadException::class.java) { validate(root(direction)) }
            assertEquals("word/document.xml", failure.part)
            assertTrue(failure.sourceContext.path.orEmpty().contains("textDirection"))
        }
    }

}
