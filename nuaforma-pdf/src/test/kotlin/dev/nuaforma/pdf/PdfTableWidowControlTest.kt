package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.DocumentLayouter
import dev.nuaforma.pdf.internal.PositionedText
import dev.nuaforma.pdf.internal.TextMeasurer
import dev.nuaforma.pdf.internal.TextMetrics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class PdfTableWidowControlTest {
    private val style = PdfTextStyle("regular", 10f)
    private val page = PdfPage(300f, 200f, PdfMargins(20f, 20f, 20f, 20f))
    private val measurer = TextMeasurer { text, _ -> TextMetrics(text.length * 5f, 10f, 2f) }

    @ParameterizedTest
    @CsvSource("2,19,1|2,|12", "3,19,1|23,|123", "3,34,12|3,|123",
        "4,19,1|234,|1234", "4,34,12|34,12|34", "4,49,123|4,12|34")
    fun wordKeepsTwoLinesOnEachSideOfATableSplit(lines: Int, room: Int, ordinary: String, withWidows: String) {
        assertEquals(ordinary.split('|'), tableLinePages(lines, room, false))
        assertEquals(withWidows.split('|'), tableLinePages(lines, room, true))
    }

    private fun tableLinePages(lines: Int, room: Int, widow: Boolean): List<String> {
        val fill = PdfParagraph(listOf(PdfTextRun("FILL", style)), spaceAfter = 0f,
            format = PdfParagraphFormat(exactLineHeight = 160f - room))
        val runs = buildList<PdfInline> {
            for (number in 1..lines) {
                if (number != 1) add(PdfLineBreak(style))
                add(PdfTextRun(number.toString(), style))
            }
        }
        val paragraph = PdfParagraph(runs, spaceAfter = 0f,
            format = PdfParagraphFormat(exactLineHeight = 14f, widowControl = widow))
        val cell = PdfTableCell(listOf(paragraph), columnSpan = 2)
        val table = PdfTable(listOf(110f, 110f), listOf(PdfTableRow(listOf(cell), allowSplit = true)),
            cellMargins = PdfMargins(0f, 0f, 0f, 0f))
        val document = PdfDocument(page, listOf(fill, table), PdfFlow())
        val pages = DocumentLayouter.plain(document, measurer, PdfRenderControl()).pages
        return pages.map { rendered ->
            rendered.elements.filterIsInstance<PositionedText>()
                .map { it.text.trim() }.filter { it.toIntOrNull() != null }.joinToString("")
        }
    }
}
