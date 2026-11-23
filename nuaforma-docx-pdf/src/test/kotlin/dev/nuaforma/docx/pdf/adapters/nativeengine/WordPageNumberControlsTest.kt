package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordPageNumberControlsTest {
    private val xml = ConversionXml(GenerationContext())
    private val field = """<w:fldSimple w:instr="PAGE"><w:r><w:t>9</w:t></w:r></w:fldSimple>"""
    private val metadata = """<w:rPr><w:rFonts w:ascii="Liberation Sans"/><w:noProof w:val="off"/>
        <w:rStyle w:val="FooterChar"/><w:sz w:val="22"/><w:szCs w:val="22"/></w:rPr>"""

    private fun gallery(content: String, name: String = "Page Numbers (Top of Page)") =
        """<w:sdt><w:sdtPr>$metadata<w:id w:val="-1"/><w:docPartObj><w:docPartGallery w:val="$name"/>
            <w:docPartUnique w:val="on"/></w:docPartObj></w:sdtPr><w:sdtEndPr>$metadata</w:sdtEndPr><w:sdtContent>$content</w:sdtContent></w:sdt>"""

    private fun story(contents: String, kind: String = "hdr") = xml.read("""
        <w:$kind xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:z="urn:foreign">$contents</w:$kind>
        """.trimIndent().toByteArray())

    @Test
    fun `nested gallery wrappers yield the same page field and preserve adjacent text`() {
        val inline = gallery(field, "Page Numbers (Bottom of Page)")
        val root = story(gallery(gallery("<w:p><w:r><w:t>Page </w:t></w:r>$inline</w:p>")), "ftr")
        val originalField = root.descendants().single { it.name.localPart == "fldSimple" }
        assertTrue(WordPageNumberControls.normalizeSource(root))
        assertEquals(listOf("r", "fldSimple"), root.elements().single().elements().map { it.name.localPart })
        assertSame(originalField, root.elements().single().elements().last())
        assertFalse(WordPageNumberControls.normalizeSource(root))

        val body = story(gallery("<w:p>$field</w:p>"), "body")
        assertFalse(WordPageNumberControls.normalizeSource(body))
        assertEquals("sdt", body.elements().single().name.localPart)
    }

    @Test
    fun `unqualified control structures and editing properties are left intact for source diagnostics`() {
        val control = gallery("<w:p>$field</w:p>")
        val invalid = listOf(
            gallery("<w:p>$field</w:p>", "Date Picker"), gallery(field), gallery(""), gallery("text"),
            gallery("<z:p/>"), gallery("<w:tbl/>"), control.replace("<w:sdtContent>", "<w:sdtContent w:val=\"1\">"),
            control.replace("<w:sdtContent><w:p>$field</w:p></w:sdtContent>", ""),
            control.replace("<w:sdtPr>", "<w:sdtPr w:val=\"1\">"), control.replace("<w:sdtPr>", "<w:sdtPr>text"),
            control.replace("<w:sdtPr>", "<w:sdtPr><w:dataBinding/>"), control.replace("<w:sdtPr>", "<w:sdtPr><z:id/>"),
            control.replace("<w:id w:val=\"-1\"/>", ""), control.replace("<w:id w:val=\"-1\"/>", "<w:id w:val=\"1\"/><w:id w:val=\"2\"/>"),
            control.replace("w:val=\"-1\"", "w:val=\"bad\""), control.replace("w:val=\"-1\"", "w:unknown=\"1\""),
            control.replace("w:val=\"-1\"", "z:val=\"1\""), control.replace("w:val=\"-1\"/>", "w:val=\"-1\">text</w:id>"),
            control.replace("w:val=\"-1\"/>", "w:val=\"-1\"><w:r/></w:id>"), control.replace("<w:docPartGallery w:val=\"Page Numbers (Top of Page)\"/>", ""),
            control.replace("w:docPartObj", "w:docPartList"), control.replace("w:sdtPr", "w:unknown"),
            control.replace("w:val=\"on\"", "w:val=\"invalid\""), control.replace("w:ascii=\"Liberation Sans\"", ""),
            control.replace("Liberation Sans", " "), control.replace("Liberation Sans", "x".repeat(129)),
            control.replace("w:val=\"off\"", "w:val=\"invalid\""), control.replace("FooterChar", ""),
            control.replace("FooterChar", "x".repeat(129)), control.replace("w:val=\"22\"", "w:val=\"0\""),
            control.replace("w:val=\"22\"", "w:val=\"abc\""),
        )
        for ((index, input) in invalid.withIndex()) {
            val root = story(input)
            val before = xml.write(root)
            assertFalse(WordPageNumberControls.normalizeSource(root), "Malformed control case $index")
            assertArrayEquals(before, xml.write(root))
        }
    }

    @Test
    fun `valid nested controls are preserved when a sibling prevents atomic normalization`() {
        val root = story(gallery("<w:p>${gallery(field)}${gallery(field, "Unqualified")}</w:p>"))
        val before = xml.write(root)
        assertFalse(WordPageNumberControls.normalizeSource(root))
        assertArrayEquals(before, xml.write(root))
    }
}
