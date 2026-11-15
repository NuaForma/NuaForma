package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.nativeengine.NativeLineBreaks
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeLineBreakSourceTest {
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private fun validate(controls: String) {
        val context = GenerationContext()
        val root = ConversionXml(context).read("""<w:document xmlns:w="$word"><w:body><w:p><w:r>$controls</w:r></w:p></w:body></w:document>""".toByteArray())
        NativeLineBreaks.validateSource(root, context) { _, message -> throw IllegalArgumentException(message) }
    }

    @Test
    fun `original schema controls retain repeated breaks and valid unsupported policies for later assessment`() {
        for (type in listOf(null, "textWrapping", "page", "column")) for (clear in listOf(null, "none", "left", "right", "all")) {
            val attributes = (type?.let { " w:type=\"$it\"" }.orEmpty() + clear?.let { " w:clear=\"$it\"" }.orEmpty())
            assertDoesNotThrow { validate("<w:br$attributes/><w:br/><w:cr/>") }
        }
    }

    @Test
    fun `invalid original values namespaces attributes and children cannot disappear during binding`() {
        for (invalid in listOf("<w:br w:type=\"invalid\"/>", "<w:br w:clear=\"invalid\"/>", "<w:br type=\"page\"/>",
            "<w:cr w:type=\"textWrapping\"/>", "<x:br xmlns:x=\"urn:future\"/>", "<w:br><w:t>hidden</w:t></w:br>", "<w:br>text</w:br>")) {
            val error = assertThrows(IllegalArgumentException::class.java) { validate(invalid) }
            assertEquals("Invalid original Word line break control", error.message)
        }
        assertDoesNotThrow { validate("""<a:r xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:br/></a:r>""") }
    }
}
