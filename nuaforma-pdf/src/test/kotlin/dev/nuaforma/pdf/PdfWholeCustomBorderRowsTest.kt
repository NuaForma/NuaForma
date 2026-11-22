package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.DocumentLayouter
import dev.nuaforma.pdf.internal.TextMeasurer
import dev.nuaforma.pdf.internal.TextMetrics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PdfWholeCustomBorderRowsTest {
    private val page = PdfPage(280f, 200f, PdfMargins(30f, 30f, 30f, 30f))
    private val style = PdfTextStyle("regular", 10f)
    private val measure = TextMeasurer { value, _ -> TextMetrics(value.length * 5f, 8f, 2f) }
    private val outline = PdfBorder(.5f, PdfColor(34, 51, 68))
    private val line = PdfEdgeStroke(outline)
    private val thin = PdfEdgeStroke(PdfBorder(.25f, outline.color))

    private fun paragraph(value: String, height: Float = 15f) =
        PdfParagraph(listOf(PdfTextRun(value, style)), 0f, 0f, format = PdfParagraphFormat(exactLineHeight = height))

    private fun document(narrowBoundary: Int, allowSplit: Boolean, firstPieceFits: Boolean = false): PdfDocument {
        val rows = (1..6).map { row ->
            val borders = PdfCellBorders(if (row == narrowBoundary + 1) thin else line, line,
                if (row == narrowBoundary) thin else line, line)
            val text = if (row == 3 && firstPieceFits) listOf(paragraph("R$row", 10f), paragraph("Tail", 10f))
            else listOf(paragraph("R$row"))
            PdfTableRow(listOf(PdfTableCell(text, borders = borders)), allowSplit)
        }
        val table = PdfTable(listOf(130f), rows, cellMargins = PdfMargins(0f, 4f, 0f, 4f), border = outline)
        return PdfDocument(page, listOf(paragraph("Before", if (firstPieceFits) 92f else 103f), table), PdfFlow())
    }

    @Test
    fun `a whole row moves between matching outline strokes for both Word split settings`() {
        for (allow in listOf(true, false)) {
            val pages = DocumentLayouter.plain(document(5, allow), measure, PdfRenderControl()).pages
            assertEquals(listOf(listOf("R1", "R2"), listOf("R3", "R4", "R5", "R6")), pages.map { page ->
                DocumentLayouter.texts(page.elements).map { it.text }.filter { it.startsWith('R') }.toList()
            })
        }
    }

    @Test
    fun `a differing continuation stroke or required row fragment retains the diagnostic`() {
        for (value in listOf(document(2, true), document(5, true, firstPieceFits = true))) {
            val failure = assertThrows(PdfRenderException::class.java) {
                DocumentLayouter.plain(value, measure, PdfRenderControl())
            }
            assertEquals("Conflicting borders in fragmented rows are not qualified", failure.message)
        }
    }
}
