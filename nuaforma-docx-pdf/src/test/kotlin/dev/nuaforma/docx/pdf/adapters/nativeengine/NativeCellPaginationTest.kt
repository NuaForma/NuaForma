package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.PdfFixture
import dev.nuaforma.pdf.PdfParagraph
import dev.nuaforma.pdf.PdfTable
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import javax.xml.namespace.QName

class NativeCellPaginationTest {
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val xml = ConversionXml(GenerationContext())

    private fun root(content: String) = xml.read("""<w:document xmlns:w="$word"><w:body><w:p><w:pPr>$content</w:pPr><w:r><w:t>Keep</w:t></w:r></w:p></w:body></w:document>""".toByteArray())

    @Test
    fun `all qualified original flags validate without rewriting the source`() {
        for (name in listOf("keepLines", "widowControl")) for (value in listOf(null, "0", "1", "false", "true", "off", "on")) {
            val source = root("<w:$name" + (value?.let { " w:val=\"$it\"" } ?: "") + "/>")
            val original = xml.write(source)
            NativeCellPagination.validateSource(source) { _, message -> error(message) }
            assertArrayEquals(original, xml.write(source))
        }
    }

    @Test
    fun `foreign duplicate malformed and nonempty originals cannot disappear through binding`() {
        for (name in listOf("keepLines", "widowControl")) {
            for (content in listOf("<w:$name/><w:$name/>", "<w:$name w:val=\"invalid\"/>",
                "<w:$name val=\"true\"/>", "<x:$name xmlns:x=\"urn:future\"/>",
                "<w:$name><w:r/></w:$name>", "<w:$name>value</w:$name>")) {
                var focused = false
                assertThrows(IllegalStateException::class.java) {
                    NativeCellPagination.validateSource(root(content)) { node, message ->
                        assertEquals(name, node.name.localPart)
                        focused = true
                        error(message)
                    }
                }
                assertTrue(focused)
            }
        }
    }

    @Test
    fun `body paragraph keeps survive while ordinary body cells omit the qualified Word flags`() {
        val source = requireNotNull(javaClass.getResourceAsStream("/cell-pagination-defaults/widows-30/a4/source.docx")).use { it.readBytes() }
        val parts = PdfFixture.unzip(source).toMutableMap()
        val document = xml.read(parts.getValue("word/document.xml"))
        val body = document.elements().single { it.name.localPart == "body" }
        val properties = body.elements().first { it.name.localPart == "p" }.elements().single { it.name.localPart == "pPr" }
        properties.elements().single { it.name.localPart == "widowControl" }.attributes[QName(word, "val", "w")] = "1"
        properties.children += XmlElement(QName(word, "keepLines", "w"))
        parts["word/document.xml"] = xml.write(document)
        val result = NativeDocxReader(GenerationContext()) { _, _, _ -> "fixture" }.read(PdfFixture.zip(parts))
        val ordinary = result.blocks.filterIsInstance<PdfParagraph>().first()
        assertTrue(ordinary.format.keepLines)
        assertTrue(ordinary.format.widowControl)
        val cells = result.blocks.filterIsInstance<PdfTable>().single().rows.flatMap { it.cells }.flatMap { it.blocks }.filterIsInstance<PdfParagraph>()
        assertFalse(cells.isEmpty())
        assertTrue(cells.all { !it.format.keepLines && !it.format.widowControl })
    }
}
