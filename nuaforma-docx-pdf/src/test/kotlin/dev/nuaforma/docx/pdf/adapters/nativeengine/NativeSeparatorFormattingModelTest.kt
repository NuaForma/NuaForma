package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.pdf.PdfNoteKind
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeSeparatorFormattingModelTest {
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val xml = ConversionXml(GenerationContext())

    private fun root(runs: String) = xml.read("""<w:footnotes xmlns:w="$word"><w:footnote w:type="separator" w:id="-1"><w:p>$runs</w:p></w:footnote></w:footnotes>""".toByteArray())

    private fun validate(root: XmlElement) = NativeDocxNotes(mapOf(PdfNoteKind.FOOTNOTE to root), NativeDocxNotes.defaults(), { error(it) })

    @Test
    fun `nonprinting run properties preserve original XML and require exactly one separator`() {
        for (properties in listOf("", "<w:rPr/>", "<w:rPr><w:sz w:val=\"24\"/></w:rPr>", "<w:rPr><w:sz w:val=\"36\"/></w:rPr>")) {
            val root = root("<w:r>$properties<w:separator/></w:r>")
            val before = xml.write(root)
            NativeDocxNotes.validateSeparatorSource(root, {}) { error(it) }
            validate(root)
            assertArrayEquals(before, xml.write(root))
        }
    }

    @Test
    fun `a special note reference is nonprinting only before its own separator control`() {
        val reference = "<w:r><w:rPr><w:rStyle w:val=\"FootnoteReference\"/></w:rPr><w:footnoteRef/></w:r>"
        val control = "<w:r><w:separator/></w:r>"
        assertNotNull(validate(root(reference + control)))
        for (runs in listOf(control + reference, reference + reference + control, reference,
            reference.replace("footnoteRef", "endnoteRef") + control,
            "<w:r><w:footnoteRef/><w:separator/></w:r>")) {
            assertThrows(IllegalStateException::class.java) { validate(root(runs)) }
        }
    }

    @Test
    fun `raw invalid properties and reference attributes cannot disappear in typed binding`() {
        for (runs in listOf("<w:r><w:rPr x=\"1\"/><w:separator/></w:r>",
            "<w:r><w:rPr><w:sz val=\"24\"/></w:rPr><w:separator/></w:r>",
            "<w:r><w:rPr/><w:rPr/><w:separator/></w:r>",
            "<w:r><w:footnoteRef w:id=\"1\"/></w:r><w:r><w:separator/></w:r>")) {
            var focus: XmlElement? = null
            assertThrows(IllegalStateException::class.java) {
                NativeDocxNotes.validateSeparatorSource(root(runs), { focus = it }) { error(it) }
            }
            assertNotNull(focus)
        }
    }
}
