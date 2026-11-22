package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.PdfFixture
import dev.nuaforma.pdf.PdfPageBreak
import dev.nuaforma.pdf.PdfParagraph
import dev.nuaforma.pdf.PdfTextRun
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NativePostBreakBookmarkTest {
    private fun read(spaces: String = "", prefix: String = ""): PdfParagraph {
        val parts = PdfFixture.parts()
        val part = "word/document.xml"
        val before = "<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Hello {name}</w:t></w:r></w:p>"
        val replacement = """<w:p>$prefix<w:r><w:br w:type="page"/></w:r>
            <w:bookmarkStart w:id="1" w:name="Target"/>$spaces</w:p>
            <w:p><w:r><w:t>Following text</w:t></w:r><w:bookmarkEnd w:id="1"/></w:p>"""
        val original = parts.getValue(part).decodeToString()
        require(before in original)
        parts[part] = original.replace(before, replacement).toByteArray()
        return NativeDocxReader(GenerationContext()) { _, _, _ -> "regular" }.read(PdfFixture.zip(parts))
            .blocks.filterIsInstance<PdfParagraph>().first()
    }

    @Test
    fun `a bookmark after a standalone break stays on its source page`() {
        val space = """<w:r><w:t xml:space="preserve"> </w:t></w:r>"""
        for (suffix in listOf("", space)) {
            val block = read(suffix)
            val pageBreak = block.runs.first() as PdfPageBreak
            assertEquals(listOf("Target"), pageBreak.destinations.map { it.name })
            assertEquals(emptyList<Any>(), block.endDestinations)
            assertEquals(if (suffix.isEmpty()) "" else " ", block.runs.filterIsInstance<PdfTextRun>().joinToString("") { it.text })
        }
    }

    @Test
    fun `visible content around a break keeps the explicit profile restriction`() {
        val visible = "<w:r><w:t>Before break</w:t></w:r>"
        val decorated = """<w:r><w:rPr><w:u w:val="single"/></w:rPr><w:t xml:space="preserve"> </w:t></w:r>"""
        for ((prefix, suffix) in listOf(visible to "", "" to decorated)) {
            val error = assertThrows(NativeDocxReadException::class.java) { read(suffix, prefix) }
            assertEquals("Paragraph-end bookmarks require ordinary body text", error.message)
        }
    }
}
