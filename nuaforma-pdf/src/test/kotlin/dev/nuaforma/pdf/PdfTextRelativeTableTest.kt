package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.DocumentLayouter
import dev.nuaforma.pdf.internal.PositionedRectangle
import dev.nuaforma.pdf.internal.TextMeasurer
import dev.nuaforma.pdf.internal.TextMetrics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PdfTextRelativeTableTest {
    private val page = PdfPage(320f, 360f, PdfMargins(20f, 20f, 20f, 20f))
    private val style = PdfTextStyle("regular", 10f)
    private val measure = TextMeasurer { value, _ -> TextMetrics(value.length * 5f, 8f, 2f) }

    private fun paragraph(value: String) = PdfParagraph(listOf(PdfTextRun(value, style)), 0f, 0f, format = PdfParagraphFormat(exactLineHeight = 14f))

    private fun document(blankLines: Int, offset: Float = 1.8f, flow: PdfFlow? = PdfFlow()): PdfDocument {
        val rows = (1..4).map { PdfTableRow(listOf(PdfTableCell(listOf(paragraph("Cell-$it"))))) }
        val table = PdfTable(listOf(200f), rows, cellMargins = PdfMargins(0f, 5f, 0f, 5f), border = null)
            .withTextRelativeTopOffset(offset)
        return PdfDocument(page, listOf(paragraph("Before"), paragraph(" "), paragraph(" "), table) +
            List(blankLines) { paragraph(" ") } + paragraph("After"), flow)
    }

    @Test
    fun fixedTableUsesTheTextRelativeOffsetWithoutChangingFollowingFlow() {
        val pageLayout = DocumentLayouter.plain(document(12), measure, PdfRenderControl()).pages.single()
        val graphics = pageLayout.elements.filterIsInstance<PositionedRectangle>()
        assertEquals(4, graphics.size)
        assertEquals(63.8f, graphics.first().top, .001f)
        val text = DocumentLayouter.texts(pageLayout.elements).associate { it.text to it.baseline }
        assertEquals(210f, text.getValue("After") - text.getValue("Before"), .001f)
    }

    @Test
    fun followingVisibleContentCannotOverlapTheTableSilently() {
        val failure = assertThrows(PdfRenderException::class.java) {
            DocumentLayouter.plain(document(2), measure, PdfRenderControl())
        }
        assertEquals(PdfFailureCode.UNSUPPORTED_TEXT, failure.code)
        assertEquals("Body content beside a floating table requires text wrapping", failure.message)
    }

    @Test
    fun floatingTableMustFitItsAnchorPageAndUseFlow() {
        val overflow = assertThrows(PdfRenderException::class.java) {
            DocumentLayouter.plain(document(12, 300f), measure, PdfRenderControl())
        }
        assertEquals(PdfFailureCode.CONTENT_OVERFLOW, overflow.code)
        assertEquals("Floating table exceeds its anchor page; required=56.0 pt; available=-22.0 pt", overflow.message)
        assertThrows(PdfRenderException::class.java) {
            DocumentLayouter.plain(document(12, flow = null), measure, PdfRenderControl())
        }
    }
}
