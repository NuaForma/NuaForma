package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.PositionedTableBorder
import dev.nuaforma.pdf.internal.TableLayouter
import dev.nuaforma.pdf.internal.TextMeasurer
import dev.nuaforma.pdf.internal.TextMetrics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PdfMergedHorizontalBorderTest {
    private val measure = TextMeasurer { text, _ -> TextMetrics(text.length * 4f, 8f, 2f) }
    private val black = PdfEdgeStroke(PdfBorder(.5f, PdfColor(0, 0, 0)), 2L)
    private val blue = PdfEdgeStroke(PdfBorder(.5f, PdfColor(32, 88, 170)), 1L)

    private fun table(top: PdfEdgeStroke = blue, side: PdfEdgeStroke? = null): PdfTable {
        val paragraph = PdfParagraph(listOf(PdfTextRun("Cell", PdfTextStyle("regular", 10f))),
            format = PdfParagraphFormat(exactLineHeight = 14f))
        val merged = PdfTableCell(listOf(paragraph), columnSpan = 2, borders = PdfCellBorders(bottom = black, left = side))
        val flat = PdfTableCell(listOf(paragraph), borders = PdfCellBorders(top = top))
        return PdfTable(listOf(80f, 60f), listOf(PdfTableRow(listOf(merged)), PdfTableRow(listOf(flat, flat))), border = null)
    }

    @Test
    fun `equal-width horizontal strokes can meet at a merged row`() {
        val measured = TableLayouter.measure(table(), measure, 200f)
        val picture = mutableListOf<dev.nuaforma.pdf.internal.PositionedElement>()
        TableLayouter.slice(measured, 0f, measured.contentEnd, 0f, 0f, picture)
        val strokes = picture.filterIsInstance<PositionedTableBorder>()
        assertTrue(strokes.isNotEmpty())
        assertTrue(strokes.all { it.horizontal })
        assertEquals(setOf(black, blue), strokes.map { it.stroke }.toSet())
    }

    @Test
    fun `mixed widths and vertical custom sides still require supported merge geometry`() {
        val thick = PdfEdgeStroke(PdfBorder(.75f, blue.border.color), blue.priority)
        for (source in listOf(table(top = thick), table(side = black))) {
            assertEquals(PdfFailureCode.UNSUPPORTED_TEXT,
                assertThrows(PdfRenderException::class.java) { TableLayouter.measure(source, measure, 200f) }.code)
        }
    }
}
