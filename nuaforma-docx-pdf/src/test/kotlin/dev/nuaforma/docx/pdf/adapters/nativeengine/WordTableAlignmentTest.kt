package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.pdf.PdfTableAlignment
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordTableAlignmentTest {
    private val context = GenerationContext()
    private val xml = ConversionXml(context)
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private fun reject(node: XmlElement, message: String): Nothing = throw NativeDocxReadException(message, "word/document.xml")

    @Test
    fun `start and end aliases retain their qualified LTR alignment`() {
        for ((value, expected) in mapOf("left" to PdfTableAlignment.LEFT, "start" to PdfTableAlignment.LEFT,
            "center" to PdfTableAlignment.CENTER, "right" to PdfTableAlignment.RIGHT, "end" to PdfTableAlignment.RIGHT)) {
            val source = xml.read("""<w:jc xmlns:w="$word" w:val="$value"/>""".toByteArray())
            assertEquals(expected, WordTableAlignment.read(source, ::reject))
        }
    }

    @Test
    fun `duplicate and foreign original alignment nodes retain the failing element`() {
        for (content in listOf("""<w:jc w:val="left"/><w:jc w:val="right"/>""",
            """<foreign:jc xmlns:foreign="urn:nuaforma-test" w:val="center"/>""")) {
            val root = xml.read("""<w:document xmlns:w="$word"><w:tblPr>$content</w:tblPr></w:document>""".toByteArray())
            val failure = assertThrows(NativeDocxReadException::class.java) { WordTableAlignment.validateSource(root, context, ::reject) }
            assertEquals("word/document.xml", failure.part)
        }
    }

}
