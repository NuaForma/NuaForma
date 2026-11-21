package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.DocumentLayouter
import dev.nuaforma.pdf.internal.TextMeasurer
import dev.nuaforma.pdf.internal.TextMetrics
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PdfSingleLineCellPaginationTest {
    private val font = PdfTextStyle("regular", 10f)
    private val page = PdfPage(300f, 200f, PdfMargins(20f, 20f, 20f, 20f))
    private val metrics = TextMeasurer { text, _ -> TextMetrics(text.length * 5f, 10f, 2f) }

    @Test
    fun `keep lines and widow control leave a single line in a spanning cell unchanged`() {
        val runs = listOf(PdfTextRun("CELL", font))
        val baseline = render(runs, keep = false, widow = false)
        assertEquals(baseline, render(runs, keep = true, widow = false))
        assertEquals(baseline, render(runs, keep = false, widow = true))
    }

    @Test
    fun `packing several physical lines still rejects unsupported cell keeps`() {
        val runs = listOf(PdfTextRun("FIRST", font), PdfLineBreak(font), PdfTextRun("SECOND", font))
        for ((keep, widow) in listOf(true to false, false to true)) {
            val error = assertThrows(PdfRenderException::class.java) { render(runs, keep, widow) }
            assertEquals(PdfFailureCode.UNSUPPORTED_TEXT, error.code)
            assertEquals("Paragraph pagination rules inside table cells are not qualified", error.message)
        }
    }

    private fun render(runs: List<PdfInline>, keep: Boolean, widow: Boolean): List<*> {
        val paragraph = PdfParagraph(runs,
            format = PdfParagraphFormat(exactLineHeight = 20f, keepLines = keep, widowControl = widow))
        val cell = PdfTableCell(listOf(paragraph), columnSpan = 2)
        val table = PdfTable(listOf(100f, 100f), listOf(PdfTableRow(listOf(cell))))
        return DocumentLayouter.plain(PdfDocument(page, listOf(table), PdfFlow()), metrics, PdfRenderControl()).pages
    }
}
