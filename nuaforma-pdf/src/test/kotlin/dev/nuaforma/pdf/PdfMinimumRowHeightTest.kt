package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PdfMinimumRowHeightTest {
    private val style = PdfTextStyle("regular", 10f)
    private val measure = TextMeasurer { text, _ -> TextMetrics(text.length * 5f, 8f, 2f) }

    private fun paragraph(value: String) = PdfParagraph(listOf(PdfTextRun(value, style)), 0f, 0f,
        PdfParagraphFormat(exactLineHeight = 14f, widowControl = false))

    private fun table(rows: List<PdfTableRow>) = PdfTable(listOf(100f), rows,
        cellMargins = PdfMargins(0f, 0f, 0f, 0f), border = null)

    @Test
    fun `minimum floors retain natural content and distribute vertical alignment space`() {
        val cells = PdfVerticalAlignment.entries.map { alignment ->
            PdfTableCell(listOf(paragraph(alignment.name)), verticalAlignment = alignment)
        }
        val source = PdfTable(listOf(60f, 60f, 60f), listOf(PdfTableRow(cells, minimumHeight = 40f)),
            cellMargins = PdfMargins(2f, 3f, 4f, 3f), border = null)
        val layout = TableLayouter.measure(source, measure, 180f)
        assertEquals(listOf(40f), layout.heights)
        val painted = mutableListOf<PositionedElement>()
        TableLayouter.slice(layout, 0f, layout.contentEnd, 0f, 0f, painted)
        val positions = painted.filterIsInstance<PositionedText>().associate { it.text to it.baseline }
        assertEquals(10f, positions.getValue("CENTER") - positions.getValue("TOP"), .001f)
        assertEquals(20f, positions.getValue("BOTTOM") - positions.getValue("TOP"), .001f)
        val small = table(listOf(PdfTableRow(listOf(PdfTableCell(listOf(paragraph("Natural")))), minimumHeight = 5f)))
        assertEquals(listOf(14f), TableLayouter.measure(small, measure, 100f).heights)
    }

    @Test
    fun `spans nested tables and normalization retain each row floor`() {
        val span = table(listOf(PdfTableRow(listOf(PdfTableCell(listOf(paragraph("Span")), rowSpan = 2)), minimumHeight = 20f),
            PdfTableRow(emptyList(), minimumHeight = 30f)))
        assertEquals(listOf(20f, 30f), TableLayouter.measure(span, measure, 100f).heights)
        val inner = table(listOf(PdfTableRow(listOf(PdfTableCell(listOf(paragraph("Nested")))), minimumHeight = 45f)))
        val outer = table(listOf(PdfTableRow(listOf(PdfTableCell(listOf(inner))), minimumHeight = 100f)))
        val normalized = DocumentTransforms.normalized(PdfDocument(PdfPage(200f, 240f, PdfMargins(20f, 20f, 20f, 20f)), listOf(outer)))
        val copied = normalized.blocks.single() as PdfTable
        assertEquals(100f, copied.rows.single().minimumHeight)
        assertEquals(45f, (copied.rows.single().cells.single().blocks.single() as PdfTable).rows.single().minimumHeight)
        assertEquals(listOf(100f), TableLayouter.measure(copied, measure, 160f).heights)
    }

    @Test
    fun `page-band field reconstruction preserves minimum height`() {
        val field = PdfParagraph(listOf(PdfPageNumber(style)), 0f, 0f, PdfParagraphFormat(exactLineHeight = 14f, widowControl = false))
        val header = table(listOf(PdfTableRow(listOf(PdfTableCell(listOf(field))), minimumHeight = 30f)))
        val doc = DocumentTransforms.normalized(PdfDocument(PdfPage(200f, 180f, PdfMargins(60f, 20f, 20f, 20f)),
            listOf(paragraph("Body")), PdfFlow(header = PdfPageBand(5f, 45f, listOf(header)))))
        val pages = FlowLayouter.layout(doc, measure)
        val decorated = FlowLayouter.decorate(pages, requireNotNull(doc.flow), 1, 1, measure)
        assertTrue(decorated.single().elements.filterIsInstance<PositionedRectangle>().any { it.height == 30f })
    }

    @Test
    fun `active floors move intact with repeated headers and impossible floors reject`() {
        val header = PdfTableRow(listOf(PdfTableCell(listOf(paragraph("Header")))), minimumHeight = 15f)
        val row = PdfTableRow(listOf(PdfTableCell(listOf(paragraph("Data")))), allowSplit = true, minimumHeight = 60f)
        val rows = PdfTable(listOf(100f), listOf(header, row), cellMargins = PdfMargins(0f, 0f, 0f, 0f), border = null, repeatedHeaderRows = 1)
        val first = paragraph("Before").copy(format = PdfParagraphFormat(exactLineHeight = 50f))
        val page = PdfPage(140f, 140f, PdfMargins(20f, 20f, 20f, 20f))
        val layouts = FlowLayouter.layout(PdfDocument(page, listOf(first, rows), PdfFlow()), measure)
        assertEquals(2, layouts.size)
        assertFalse(layouts.first().elements.filterIsInstance<PositionedText>().any { it.text == "Header" })
        assertTrue(layouts.last().elements.filterIsInstance<PositionedRectangle>().any { it.height == 60f })
        val impossible = table(listOf(PdfTableRow(listOf(PdfTableCell(listOf(paragraph("Small")))), allowSplit = true, minimumHeight = 101f)))
        assertEquals(PdfFailureCode.CONTENT_OVERFLOW, assertThrows(PdfRenderException::class.java) {
            FlowLayouter.layout(PdfDocument(page, listOf(impossible), PdfFlow()), measure)
        }.code)
    }

    @Test
    fun `natural content above a small floor retains all fragmented lines`() {
        val row = PdfTableRow(listOf(PdfTableCell((0 until 40).map { paragraph("L${it.toString().padStart(2, '0')}") })),
            allowSplit = true, minimumHeight = 30f)
        val page = PdfPage(140f, 160f, PdfMargins(20f, 20f, 20f, 20f))
        val pages = FlowLayouter.layout(PdfDocument(page, listOf(table(listOf(row))), PdfFlow()), measure)
        assertTrue(pages.size > 1)
        assertEquals(40, pages.flatMap { it.elements }.filterIsInstance<PositionedText>().count { it.text.startsWith("L") })
        for (invalid in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY, 14_401f)) {
            assertThrows(IllegalArgumentException::class.java) { PdfTableRow(row.cells, minimumHeight = invalid) }
        }
        assertNull(PdfTableRow(row.cells).minimumHeight)
    }

}
