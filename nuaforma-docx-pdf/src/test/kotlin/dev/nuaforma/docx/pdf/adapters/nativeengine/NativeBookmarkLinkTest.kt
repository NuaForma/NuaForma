package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.PdfFixture
import dev.nuaforma.pdf.PdfLink
import dev.nuaforma.pdf.PdfParagraph
import dev.nuaforma.pdf.PdfTextRun
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NativeBookmarkLinkTest {
    private fun read(anchor: String, runs: String): List<PdfTextRun> {
        val parts = PdfFixture.parts()
        val original = "<w:r><w:rPr><w:b/></w:rPr><w:t>Hello {name}</w:t></w:r>"
        val bookmark = """<w:bookmarkStart w:id="1" w:name="ActualTarget"/><w:r><w:t>Target</w:t></w:r><w:bookmarkEnd w:id="1"/>"""
        val link = """<w:hyperlink w:anchor="$anchor">$runs</w:hyperlink>"""
        val path = "word/document.xml"
        val source = parts.getValue(path).decodeToString()
        require(source.contains(original))
        parts[path] = source.replace(original, bookmark + link).toByteArray()
        val document = NativeDocxReader(GenerationContext()) { _, _, _ -> "regular" }.read(PdfFixture.zip(parts))
        return (document.blocks.single() as PdfParagraph).runs.filterIsInstance<PdfTextRun>()
    }

    @Test
    fun missingOrdinaryBookmarkRetainsTheVisibleRun() {
        val runs = """<w:r><w:rPr><w:u w:val="single"/></w:rPr><w:t>Link label</w:t></w:r>"""
        val label = read("MissingBookmark", runs).last()
        assertEquals("Link label", label.text)
        assertEquals(true, label.style.underline)
        assertNull(label.navigation)
    }

    @Test
    fun caseInsensitiveExistingBookmarkUsesTheOriginalDestinationName() {
        val label = read("actualtarget", "<w:r><w:t>Link label</w:t></w:r>").last()
        assertEquals(PdfLink.Internal("ActualTarget"), label.navigation?.link)
    }

    @Test
    fun missingBookmarkDoesNotQualifyANestedField() {
        val field = """<w:fldSimple w:instr="PAGEREF MissingBookmark \h"><w:r><w:t>1</w:t></w:r></w:fldSimple>"""
        val failure = assertThrows(NativeDocxReadException::class.java) { read("MissingBookmark", field) }
        assertEquals("An unresolved bookmark cannot qualify a field inside its hyperlink", failure.message)
    }
}
