package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.pdf.PdfMargins
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordWhitespacePaddingTest {
    private val context = GenerationContext()
    private val xml = ConversionXml(context)
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val wp = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"

    private fun reject(node: XmlElement, message: String): Nothing = throw NativeDocxReadException(message, "word/document.xml")

    @Test
    fun `only explicit XML preservation retains edge spaces and malformed original policies reject`() {
        for ((attribute, expected) in listOf("" to "content", "xml:space=\"default\"" to "content", "xml:space=\"preserve\"" to " content ")) {
            val node = xml.read("""<w:t xmlns:w="$word" $attribute> content </w:t>""".toByteArray())
            WordTextWhitespace.validateSource(node, context, ::reject)
            assertEquals(expected, WordTextWhitespace.visible(node, " content "))
        }
        for (attribute in listOf("xml:space=\"invalid\"", "w:space=\"preserve\"", "space=\"preserve\"")) {
            val node = xml.read("""<w:t xmlns:w="$word" $attribute> content </w:t>""".toByteArray())
            assertThrows(NativeDocxReadException::class.java) { WordTextWhitespace.validateSource(node, context, ::reject) }
        }
    }

    @Test
    fun `image extents retain original bounds namespaces structure and duplicate checks`() {
        val good = xml.read("""<wp:effectExtent xmlns:wp="$wp" l="12700" t="25400" r="38100" b="50800"/>""".toByteArray())
        assertEquals(PdfMargins(2f, 3f, 4f, 1f), WordInlineImagePadding.read(good, ::reject))
        for (attributes in listOf("l=\"0\" t=\"0\" r=\"0\"", "l=\"0\" t=\"0\" r=\"-1\" b=\"0\"", "l=\"0\" t=\"0\" r=\"182880001\" b=\"0\"", "l=\"0\" t=\"0\" r=\"0.5\" b=\"0\"", "l=\"0\" t=\"0\" r=\"0\" b=\"0\" extra=\"0\"")) {
            val node = xml.read("""<wp:effectExtent xmlns:wp="$wp" $attributes/>""".toByteArray())
            assertThrows(NativeDocxReadException::class.java) { WordInlineImagePadding.read(node, ::reject) }
        }
        val duplicate = xml.read("""<wp:inline xmlns:wp="$wp"><wp:effectExtent l="0" t="0" r="0" b="0"/><wp:effectExtent l="0" t="0" r="0" b="0"/></wp:inline>""".toByteArray())
        assertThrows(NativeDocxReadException::class.java) { WordInlineImagePadding.validateSource(duplicate, context, ::reject) }
        val foreign = xml.read("""<wp:inline xmlns:wp="$wp" xmlns:w="$word"><w:effectExtent l="0" t="0" r="0" b="0"/></wp:inline>""".toByteArray())
        assertThrows(NativeDocxReadException::class.java) { WordInlineImagePadding.validateSource(foreign, context, ::reject) }
    }

}
