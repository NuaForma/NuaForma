package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.PdfFixture
import dev.nuaforma.pdf.PdfDocument
import dev.nuaforma.pdf.PdfParagraph
import dev.nuaforma.pdf.PdfTextRun
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NativeSetFieldBookmarkTest {
    private fun read(tail: String = "", nested: Boolean = false): PdfDocument {
        val parts = PdfFixture.parts()
        val body = "<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Hello {name}</w:t></w:r></w:p>"
        val innerStart = if (nested) """<w:bookmarkStart w:id="3" w:name="SecondRange"/>""" else ""
        val innerEnd = if (nested) """<w:bookmarkEnd w:id="3"/>""" else ""
        val source = """<w:p><w:r><w:t>Before</w:t></w:r><w:r><w:fldChar w:fldCharType="begin"/></w:r>
            <w:r><w:instrText xml:space="preserve"> SET InertProof "4" \* MERGEFORMAT </w:instrText></w:r>
            <w:r><w:fldChar w:fldCharType="separate"/></w:r><w:bookmarkStart w:id="1" w:name="InertProof"/>
            $innerStart<w:r><w:t>4</w:t></w:r>$innerEnd<w:bookmarkEnd w:id="1"/><w:r><w:fldChar w:fldCharType="end"/></w:r>
            <w:r><w:t>After</w:t></w:r></w:p>$tail"""
        val part = "word/document.xml"
        parts[part] = parts.getValue(part).decodeToString().replace(body, source).toByteArray()
        return NativeDocxReader(GenerationContext()) { _, _, _ -> "regular" }.read(PdfFixture.zip(parts))
    }

    private fun text(document: PdfDocument) = document.blocks.filterIsInstance<PdfParagraph>()
        .map { it.runs.filterIsInstance<PdfTextRun>().joinToString("") { run -> run.text } }

    @Test
    fun assignmentOwnBookmarkAddsNoVisibleContentAndKeepsOrdinaryRef() {
        val reference = """<w:p><w:fldSimple w:instr="REF InertProof"><w:r><w:t>saved</w:t></w:r></w:fldSimple></w:p>"""
        assertEquals(listOf("BeforeAfter"), text(read()))
        assertEquals(listOf("BeforeAfter", "4"), text(read(reference)))
    }

    @Test
    fun navigationToTheSavedRangeKeepsAnExplicitDiagnostic() {
        val link = """<w:p><w:hyperlink w:anchor="inertproof"><w:r><w:t>Link</w:t></w:r></w:hyperlink></w:p>"""
        val page = """<w:p><w:fldSimple w:instr="PAGEREF InertProof"><w:r><w:t>1</w:t></w:r></w:fldSimple></w:p>"""
        for (tail in listOf(link, page)) {
            val error = assertThrows(NativeDocxReadException::class.java) { read(tail) }
            assertEquals("Navigation to a SET result bookmark is not qualified", error.message)
        }
    }

    @Test
    fun nestedBookmarkRangesAreOutsideTheQualifiedShape() {
        val error = assertThrows(NativeDocxReadException::class.java) { read(nested = true) }
        assertEquals("SET assignment conflicts with a visible bookmark", error.message)
    }
}
