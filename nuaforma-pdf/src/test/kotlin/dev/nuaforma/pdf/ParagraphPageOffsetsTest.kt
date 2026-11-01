package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ParagraphPageOffsetsTest {
    private val offset = PdfParagraphPageOffset(-18f, PdfPageSide.EVEN)
    private val style = PdfTextStyle("regular", 10f)
    private val page = PdfPage(200f, 150f, PdfMargins(20f, 20f, 20f, 20f))

    private fun paragraph() = PdfParagraph(listOf(PdfTextRun("Text", style)), 0f, 0f,
        PdfParagraphFormat(leftIndent = 40f, rightIndent = 20f, exactLineHeight = 16f)).withPageOffset(offset)

    private val measurer = TextMeasurer { text, _ -> TextMetrics(text.length * 5f, 8f, 2f) }

    @Test fun `physical parity is independent of display numbers and immutable copies retain the policy`() {
        val p = paragraph()
        val document = PdfDocument(listOf(PdfPageSequence(page, listOf(p), numbering = PdfPageNumbering(2)),
            PdfPageSequence(page, listOf(p.copy(runs = listOf(PdfTextRun("Next", style)))), numbering = PdfPageNumbering(1))))
        val result = DocumentLayouter.plain(DocumentTransforms.normalized(document), measurer, PdfRenderControl())
        assertEquals(listOf(2, 1), result.displayNumbers)
        assertEquals(listOf(60f, 42f), result.pages.map { it.elements.filterIsInstance<PositionedText>().first().x })
        assertEquals(offset, p.pageOffset)
        assertEquals(offset, p.withSpacing(PdfParagraphSpacing(true)).pageOffset)
        assertTrue(result.pages.all { it.elements.filterIsInstance<PositionedText>().all { t -> t.pageOffset == null } })
    }

    @Test fun `deferred offsets survive table placement and frame transforms and skip page anchored lines`() {
        val text = PositionedText("Text", style, 40f, 20f)
        val marked = ParagraphPageOffsets.mark(text, offset)
        val placed = FlowLayouter.translate(marked, 100f, 50f)
        val line = PositionedStroke(10f, 10f, 20f, 10f, PdfLineStroke(1f, PdfColor(0, 0, 0)), pageX = true)
        val a = ParagraphPageOffsets.resolve(PageLayout(page, listOf(placed, ParagraphPageOffsets.mark(line, offset))), 2)
        assertEquals(122f, (a.elements.first() as PositionedText).x)
        assertEquals(10f, (a.elements.last() as PositionedStroke).x)
        val box = PdfTextBox(0f, 0f, 150f, 80f, listOf(paragraph()))
        val inside = PositionedBox(box, 10f, 10f, listOf(marked))
        val b = ParagraphPageOffsets.resolve(PageLayout(page, listOf(inside)), 2)
        assertEquals(22f, ((b.elements.single() as PositionedBox).elements.single() as PositionedText).x)
    }

    @Test fun `invalid alternating geometry cannot move text outside its paragraph area`() {
        assertThrows(IllegalArgumentException::class.java) { PdfParagraphPageOffset(Float.NaN, PdfPageSide.ODD) }
        assertThrows(IllegalArgumentException::class.java) { paragraph().withPageOffset(PdfParagraphPageOffset(-100f, PdfPageSide.ODD)) }
        assertThrows(IllegalArgumentException::class.java) { paragraph().withPageOffset(PdfParagraphPageOffset(100f, PdfPageSide.ODD)) }
        assertNull(paragraph().withPageOffset(null).pageOffset)
    }

}
