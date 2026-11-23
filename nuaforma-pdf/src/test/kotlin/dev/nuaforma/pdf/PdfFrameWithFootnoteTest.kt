package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PdfFrameWithFootnoteTest {
    private val style = PdfTextStyle("regular", 10f)
    private val page = PdfPage(320f, 360f, PdfMargins(20f, 20f, 20f, 20f))
    private val measure = TextMeasurer { value, _ -> TextMetrics(value.length * 5f, 8f, 2f) }

    private fun paragraph(vararg runs: PdfInline) =
        PdfParagraph(runs.toList(), spaceAfter = 0f, format = PdfParagraphFormat(exactLineHeight = 14f))

    private fun document(frameTop: Float, emptyFirstPageNoteRegion: Boolean = false): PdfDocument {
        val definition = PdfNote("note", PdfNoteKind.FOOTNOTE, PdfListValue("notes", 0, 1, "1"),
            listOf(paragraph(PdfNoteLabel(style), PdfTextRun(" note value", style))))
        val frame = PdfTextBox(60f, frameTop, 130f, 24f, listOf(paragraph(PdfTextRun("Fixed shape", style))),
            PdfBoxAnchor.PAGE, padding = PdfMargins(2f, 2f, 2f, 2f))
        val owning = paragraph(PdfTextRun("Body", style), PdfNoteCall("call", definition.id, style))
        val blocks = if (emptyFirstPageNoteRegion) listOf(frame, paragraph(PdfTextRun("First", style)),
            owning.copy(format = owning.format.copy(pageBreakBefore = true))) else listOf(frame, owning)
        return PdfDocument(page, blocks, PdfFlow(), notes = listOf(definition))
    }

    @Test
    fun fixedBodyFrameCanPaintAboveNotesAndOnPagesWithoutNotes() {
        val normal = ReferenceLayouter.layout(document(100f), measure, PdfRenderControl())
        assertEquals(1, normal.pages.size)
        val frame = normal.pages.single().elements.filterIsInstance<PositionedBox>().single()
        assertEquals(100f, frame.top)
        assertTrue(DocumentLayouter.texts(normal.pages[0].elements).any { it.span?.id == "call" })
        val separate = ReferenceLayouter.layout(document(320f, emptyFirstPageNoteRegion = true), measure, PdfRenderControl())
        assertEquals(2, separate.pages.size)
        assertEquals(320f, separate.pages[0].elements.filterIsInstance<PositionedBox>().single().top)
    }

    @Test
    fun crossingTheRealFootnoteRegionRetainsAnExplicitFailure() {
        val failure = assertThrows(PdfRenderException::class.java) {
            ReferenceLayouter.layout(document(320f), measure, PdfRenderControl())
        }
        assertEquals(PdfFailureCode.UNSUPPORTED_TEXT, failure.code)
        assertEquals("Floating text frame overlaps the reserved footnote area", failure.message)
    }
}
