package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.DocumentLayouter
import dev.nuaforma.pdf.internal.PositionedText
import dev.nuaforma.pdf.internal.TextMeasurer
import dev.nuaforma.pdf.internal.TextMetrics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class PdfFragmentedCellAlignmentTest {
    private val style = PdfTextStyle("regular", 10f)
    private val page = PdfPage(300f, 200f, PdfMargins(20f, 20f, 20f, 20f))
    private val measurer = TextMeasurer { text, _ -> TextMetrics(text.length * 5f, 10f, 2f) }

    @ParameterizedTest
    @CsvSource("34,1,TOP,0", "34,1,CENTER,7", "34,1,BOTTOM,14",
        "49,1,CENTER,14", "49,1,BOTTOM,28", "49,2,CENTER,7",
        "19,2,CENTER,0", "19,2,BOTTOM,0")
    fun alignsWholeCellsWithinTheirFirstFragment(room: Int, leftLines: Int, alignment: PdfVerticalAlignment, offset: Float) {
        val fill = PdfParagraph(listOf(PdfTextRun("FILL", style)), spaceAfter = 0f,
            format = PdfParagraphFormat(exactLineHeight = 160f - room))
        val left = PdfTableCell(listOf(cellParagraph("LEFT", leftLines)), verticalAlignment = alignment)
        val right = PdfTableCell(listOf(cellParagraph("RIGHT", 4)))
        val table = PdfTable(listOf(110f, 110f), listOf(PdfTableRow(listOf(left, right), allowSplit = true)),
            cellMargins = PdfMargins(0f, 0f, 0f, 0f))
        val document = PdfDocument(page, listOf(fill, table), PdfFlow())
        val pages = DocumentLayouter.plain(document, measurer, PdfRenderControl()).pages
            .map { it.elements.filterIsInstance<PositionedText>() }

        assertEquals(2, pages.size)
        val first = pages.first()
        val firstLeft = first.first { it.text == "LEFT1" }.baseline
        val firstRight = first.first { it.text == "RIGHT1" }.baseline
        assertEquals(offset, firstLeft - firstRight, .001f)
        if (room == 19 && leftLines == 2) {
            val continued = pages.last()
            assertEquals(continued.first { it.text == "RIGHT2" }.baseline,
                continued.first { it.text == "LEFT2" }.baseline, .001f)
        }
    }

    private fun cellParagraph(prefix: String, count: Int): PdfParagraph {
        val runs = buildList<PdfInline> {
            for (number in 1..count) {
                if (number != 1) add(PdfLineBreak(style))
                add(PdfTextRun(prefix + number, style))
            }
        }
        return PdfParagraph(runs, spaceAfter = 0f, format = PdfParagraphFormat(exactLineHeight = 14f))
    }
}
