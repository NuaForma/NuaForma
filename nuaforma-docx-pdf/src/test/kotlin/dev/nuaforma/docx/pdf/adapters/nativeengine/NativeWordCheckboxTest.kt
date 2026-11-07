package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.pdf.PdfColor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeWordCheckboxTest {
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private fun read(box: String, extra: String = "", bold: Boolean = false) = NativeWordCheckbox.read(
        NativeDocxFields.Form(ConversionXml(GenerationContext()).read("""<w:ffData xmlns:w="$word"><w:name w:val="Check1"/><w:enabled/><w:calcOnExit w:val="0"/><w:checkBox>$box</w:checkBox>$extra</w:ffData>""".toByteArray()), null),
        11f, bold, PdfColor(0, 80, 100), {}, { throw IllegalArgumentException(it) })

    @Test
    fun `current state overrides default and automatic size follows text without a symbol font`() {
        val unchecked = read("<w:sizeAuto/><w:default w:val=\"0\"/>")
        assertEquals(1, unchecked.vector.paths.size)
        assertEquals(11f, unchecked.height)
        assertEquals(PdfColor(0, 80, 100), unchecked.color)
        val checked = read("<w:default w:val=\"0\"/><w:checked/>")
        assertEquals(3, checked.vector.paths.size)
        assertEquals(1, read("<w:default/><w:checked w:val=\"false\"/>").vector.paths.size)
        assertEquals(3, read("<w:default w:val=\"true\"/>").vector.paths.size)
    }

    @Test
    fun `explicit half-point size and bold line weight survive`() {
        val regular = read("<w:size w:val=\"24\"/><w:checked/>")
        val bold = read("<w:size w:val=\"24\"/><w:checked/>", bold = true)
        assertEquals(12f, regular.height)
        assertEquals(regular.height, bold.height)
        assertTrue(bold.vector.strokeWidth > regular.vector.strokeWidth)
        assertEquals(regular.vector.paths.size, bold.vector.paths.size)
    }

    @Test
    fun `macros bindings malformed states foreign namespaces and conflicting sizes remain rejected`() {
        for (extra in listOf("<w:entryMacro w:val=\"Run\"/>", "<w:exitMacro w:val=\"Run\"/>",
            "<w:name w:val=\"Duplicate\"/>", "<w:dataBinding/>", "<x:enabled xmlns:x=\"https://invalid.example/\"/>")) {
            assertThrows(IllegalArgumentException::class.java, { read("<w:sizeAuto/>", extra) }, extra)
        }
        for (box in listOf("<w:sizeAuto/><w:size w:val=\"20\"/>", "<w:size w:val=\"0\"/>", "<w:size w:val=\"401\"/>",
            "<w:sizeAuto w:val=\"1\"/>", "<w:checked w:val=\"maybe\"/>", "<w:default/><w:default/>",
            "<w:checked extra=\"1\"/>", "<w:checked><w:t>text</w:t></w:checked>", "text")) {
            assertThrows(IllegalArgumentException::class.java, { read(box) }, box)
        }
    }

}
