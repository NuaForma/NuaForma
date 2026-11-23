package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.DocumentLayouter
import dev.nuaforma.pdf.internal.PositionedText
import dev.nuaforma.pdf.internal.TextMeasurer
import dev.nuaforma.pdf.internal.TextMetrics
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PdfNegativeListAnchorTest {
    private val style = PdfTextStyle("regular", 12f)
    private val metrics = TextMeasurer { text, _ -> TextMetrics(text.length * 6f, 12f, 3f) }
    private val page = PdfPage(300f, 300f, PdfMargins(30f, 30f, 30f, 30f))
    private val item = PdfParagraph(
        listOf(PdfTextRun("ITEM", style)),
        format = PdfParagraphFormat(leftIndent = 2.5f, firstLineIndent = -18.05f,
            exactLineHeight = 20f, widowControl = false),
        listLabel = PdfListLabel(PdfListValue("list", 0, 1, "1."), style, tabStop = 41.9f),
    )

    @Test
    fun `a negative hanging marker can extend left of the body or a second cell`() {
        val body = coordinates(page, item)
        assertPosition(body, 14.45f, 32.5f)

        val left = PdfParagraph(listOf(PdfTextRun("CELL", style)),
            format = PdfParagraphFormat(exactLineHeight = 20f, widowControl = false))
        val padding = PdfMargins(0f, 4f, 0f, 4f)
        val table = PdfTable(listOf(120f, 100f), listOf(PdfTableRow(listOf(PdfTableCell(listOf(left)), PdfTableCell(listOf(item))))),
            cellMargins = padding)
        assertPosition(coordinates(page, table), 138.45f, 156.5f)
    }

    @Test
    fun `a negative marker cannot cross the physical left edge`() {
        val narrow = page.copy(margins = PdfMargins(30f, 30f, 30f, 5f))
        val bodyError = assertThrows(PdfRenderException::class.java) { coordinates(narrow, item) }
        assertEquals(PdfFailureCode.CONTENT_OVERFLOW, bodyError.code)

        val table = PdfTable(listOf(100f), listOf(PdfTableRow(listOf(PdfTableCell(listOf(item))))),
            cellMargins = PdfMargins(0f, 4f, 0f, 4f))
        val error = assertThrows(PdfRenderException::class.java) { coordinates(narrow, table) }
        assertEquals(PdfFailureCode.CONTENT_OVERFLOW, error.code)
        assertTrue(error.message.orEmpty().startsWith("List marker exceeds physical left page boundary; required=15."), error.message)
        assertTrue(error.message.orEmpty().endsWith("available=9.0 pt"), error.message)
    }

    private fun coordinates(address: PdfPage, block: PdfBlock): Map<String, Float> {
        val document = PdfDocument(address, listOf(block), PdfFlow())
        return DocumentLayouter.plain(document, metrics, PdfRenderControl()).pages.single().elements
            .filterIsInstance<PositionedText>().associate { it.text to it.x }
    }

    private fun assertPosition(actual: Map<String, Float>, marker: Float, text: Float) {
        assertEquals(marker, requireNotNull(actual["1."]), .001f)
        assertEquals(text, requireNotNull(actual["ITEM"]), .001f)
    }
}
