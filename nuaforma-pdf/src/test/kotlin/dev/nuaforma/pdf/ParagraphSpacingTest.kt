package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ParagraphSpacingTest {

    private fun paragraph(text: String, pageBreak: Boolean = false) = PdfParagraph(listOf(PdfTextRun(text, PdfTextStyle("regular", 10f))),
        14f, 14f, PdfParagraphFormat(exactLineHeight = 16f, collapseSpacing = true, pageBreakBefore = pageBreak))
        .withSpacing(PdfParagraphSpacing(true, true, true))

    private val measure = TextMeasurer { text, _ -> TextMetrics(text.length * 5f, 8f, 2f) }
    private val page = PdfPage(200f, 150f, PdfMargins(20f, 20f, 20f, 20f))

    @Test fun `conditional gaps preserve old constructors and combine only the requested edges`() {
        val p = paragraph("Test")
        assertEquals(14f, ParagraphSpacing.before(p, 0f))
        assertEquals(0f, ParagraphSpacing.before(p, 0f, true))
        assertEquals(0f, ParagraphSpacing.before(p, 0f, true, true))
        assertEquals(14f, ParagraphSpacing.before(p.withSpacing(p.spacing.copy(suppressBeforeAtForcedPageStart = false)), 0f, true, true))
        assertEquals(6f, ParagraphSpacing.before(p, 8f))
        assertEquals(14f, ParagraphSpacing.after(p))
        assertEquals(0f, ParagraphSpacing.after(p, true))
        val original = PdfParagraph(p.runs, 14f, 14f)
        assertEquals(14f, ParagraphSpacing.before(original, 0f, true))
        assertEquals(14f, ParagraphSpacing.after(original, true))
        assertEquals(PdfParagraphSpacing(), original.spacing)
        assertEquals(14f, p.spaceBefore)
    }

    @Test fun `normalization retains conditional gaps and forced page start policy`() {
        val source = PdfDocument(page, listOf(paragraph("First"), paragraph("Second", true)), PdfFlow())
        val normalized = DocumentTransforms.normalized(source)
        assertEquals(source.blocks.map { (it as PdfParagraph).spacing }, normalized.blocks.map { (it as PdfParagraph).spacing })
        val pages = FlowLayouter.layout(normalized, measure)
        assertEquals(2, pages.size)
        val y = pages.map { it.elements.filterIsInstance<PositionedText>().first().baseline }
        assertEquals(y.first(), y.last())
        val legacy = paragraph("Second", true).withSpacing(PdfParagraphSpacing(true, false, true))
        val older = FlowLayouter.layout(PdfDocument(page, listOf(paragraph("First"), legacy), PdfFlow()), measure)
        assertEquals(14f, older.last().elements.filterIsInstance<PositionedText>().first().baseline - y.first())
    }

    @Test fun `cell edge gaps are suppressed without changing interior gaps or row fragmentation pieces`() {
        val table = PdfTable(listOf(150f), listOf(PdfTableRow(listOf(PdfTableCell(listOf(paragraph("One"), paragraph("Two"), paragraph("Three")))))))
        val cell = TableLayouter.measure(table, measure, table.columnWidths.sum()).cells.single()
        assertEquals(76f, cell.contentHeight)
        assertEquals(listOf(0f, 30f, 60f), cell.pieces.map { it.top })
    }

}
