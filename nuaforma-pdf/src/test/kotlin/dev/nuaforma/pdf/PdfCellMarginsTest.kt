package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PdfCellMarginsTest {
    private val style = PdfTextStyle("regular", 10f)
    private val measure = TextMeasurer { text, _ -> TextMetrics(text.length * 5f, 8f, 2f) }
    private val zero = PdfMargins(0f, 0f, 0f, 0f)

    private fun paragraph(text: String) = PdfParagraph(listOf(PdfTextRun(text, style)), 0f, 0f,
        PdfParagraphFormat(exactLineHeight = 14f, widowControl = false))

    private fun paint(table: PdfTable, width: Float = 300f): List<PositionedElement> {
        val layout = TableLayouter.measure(table, measure, width)
        return mutableListOf<PositionedElement>().also { TableLayouter.slice(layout, 0f, layout.contentEnd, 0f, 0f, it) }
    }

    @Test
    fun `own horizontal margins determine wrapping while null retains exact default geometry`() {
        val margins = PdfMargins(2f, 5f, 4f, 5f)

        fun table(own: PdfMargins?) = PdfTable(listOf(75f), listOf(PdfTableRow(listOf(
            PdfTableCell(listOf(paragraph("alpha beta gamma")), margins = own)))), cellMargins = margins, border = null)

        assertEquals(paint(table(null)), paint(table(margins)))
        val base = paint(table(null)).filterIsInstance<PositionedText>()
        val narrow = paint(table(PdfMargins(2f, 15f, 4f, 20f))).filterIsInstance<PositionedText>()
        assertTrue(narrow.map { it.baseline }.distinct().size > base.map { it.baseline }.distinct().size)
        assertTrue(narrow.all { it.x >= 20f })
        assertTrue(narrow.all { it.x + it.text.length * 5f <= 60.001f })
    }

    @Test
    fun `vertical alignment uses each cell frame inside a shared minimum row`() {
        val cells = PdfVerticalAlignment.entries.mapIndexed { index, alignment ->
            PdfTableCell(listOf(paragraph(alignment.name)), verticalAlignment = alignment,
                margins = PdfMargins(2f + index * 2f, 3f, 8f, 3f))
        }
        val table = PdfTable(listOf(60f, 60f, 60f), listOf(PdfTableRow(cells, minimumHeight = 60f)),
            cellMargins = zero, border = null)
        val positions = paint(table).filterIsInstance<PositionedText>().associate { it.text to it.baseline }
        assertEquals(19f, positions.getValue("CENTER") - positions.getValue("TOP"), .001f)
        assertEquals(36f, positions.getValue("BOTTOM") - positions.getValue("TOP"), .001f)
    }

    @Test
    fun `intrinsic automatic and narrow fixed widths account for actual margins`() {
        val own = PdfMargins(0f, 10f, 0f, 40f)
        val cell = PdfTableCell(listOf(paragraph("AAAA")), margins = own)
        val natural = PdfTable(listOf(100f), listOf(PdfTableRow(listOf(cell))), widthPolicy = PdfTableWidthPolicy.FIT_CONTENTS)
        assertEquals(70f, TableWidthResolver.resolve(natural, measure, 100f).columnWidths.single(), .001f)
        val automatic = PdfTable(listOf(0f, 0f), listOf(PdfTableRow(listOf(cell,
            PdfTableCell(listOf(paragraph("BBBB")), margins = zero)))), widthPolicy = PdfTableWidthPolicy.AUTO_FIT,
            preferredWidth = PdfPreferredWidth.points(100f))
        val widths = TableWidthResolver.resolve(automatic, measure, 100f).columnWidths
        assertEquals(100f, widths.sum(), .01f)
        assertTrue(widths[0] >= 70f)
        assertTrue(widths[1] >= 20f)
        val narrow = PdfTable(listOf(5f), listOf(PdfTableRow(listOf(PdfTableCell(listOf(paragraph("X")), margins = zero)))))
        assertEquals("X", paint(narrow).filterIsInstance<PositionedText>().single().text)
    }

    @Test
    fun `nested percentages and merged frames retain their own content box`() {
        val child = PdfTable(listOf(0f), listOf(PdfTableRow(listOf(PdfTableCell(listOf(paragraph("CHILD")))))),
            widthPolicy = PdfTableWidthPolicy.AUTO_FIT, preferredWidth = PdfPreferredWidth.percent(80f), border = null)
        val own = PdfMargins(4f, 30f, 6f, 20f)
        val parent = PdfTable(listOf(150f), listOf(PdfTableRow(listOf(PdfTableCell(listOf(child), margins = own)))), border = null)
        val nestedFrame = paint(parent).filterIsInstance<PositionedRectangle>().single { kotlin.math.abs(it.width - 80f) < .01f }
        assertEquals(20f, nestedFrame.x, .001f)
        val merged = PdfTable(listOf(75f, 75f), listOf(
            PdfTableRow(listOf(PdfTableCell(listOf(paragraph("SPAN")), columnSpan = 2, rowSpan = 2, margins = own)), minimumHeight = 20f),
            PdfTableRow(emptyList(), minimumHeight = 30f)), cellMargins = zero, border = null)
        val elements = paint(merged)
        assertEquals(50f, elements.filterIsInstance<PositionedRectangle>().single().height, .001f)
        assertEquals(20f, elements.filterIsInstance<PositionedText>().single().x, .001f)
    }

    @Test
    fun `normalization and page field reconstruction preserve cell margins`() {
        val own = PdfMargins(4f, 5f, 6f, 20f)
        val field = PdfParagraph(listOf(PdfPageNumber(style)), 0f, 0f, PdfParagraphFormat(exactLineHeight = 14f, widowControl = false))
        val table = PdfTable(listOf(100f), listOf(PdfTableRow(listOf(PdfTableCell(listOf(field), margins = own)))), border = null)
        val page = PdfPage(200f, 180f, PdfMargins(60f, 20f, 20f, 20f))
        val normalized = DocumentTransforms.normalized(PdfDocument(page, listOf(table)))
        assertEquals(own, (normalized.blocks.single() as PdfTable).rows.single().cells.single().margins)
        val document = DocumentTransforms.normalized(PdfDocument(page, listOf(paragraph("Body")),
            PdfFlow(header = PdfPageBand(5f, 45f, listOf(table)))))
        val decorated = FlowLayouter.decorate(FlowLayouter.layout(document, measure), requireNotNull(document.flow), 1, 1, measure)
        val number = decorated.single().elements.filterIsInstance<PositionedText>().single { it.text == "1" }
        assertEquals(40f, number.x, .001f)
    }

    @Test
    fun `fragmented cells retain every line inside page bounds with their own origins`() {
        val first = PdfTableCell((0 until 40).map { paragraph("L${it.toString().padStart(2, '0')}") }, margins = PdfMargins(8f, 7f, 12f, 3f))
        val second = PdfTableCell((0 until 40).map { paragraph("R${it.toString().padStart(2, '0')}") }, margins = PdfMargins(2f, 9f, 18f, 11f))
        val table = PdfTable(listOf(100f, 100f), listOf(PdfTableRow(listOf(first, second), allowSplit = true)), border = null)
        val page = PdfPage(260f, 160f, PdfMargins(20f, 20f, 20f, 20f))
        val pages = FlowLayouter.layout(PdfDocument(page, listOf(table), PdfFlow()), measure)
        assertTrue(pages.size > 1)
        val text = pages.flatMap { it.elements }.filterIsInstance<PositionedText>()
        assertEquals(80, text.size)
        assertEquals(80, text.map { it.text }.toSet().size)
        for (position in text) {
            assertEquals(if (position.text.startsWith("L")) 23f else 131f, position.x, .001f)
            assertTrue(position.baseline in 20f..140f)
        }
    }

    @Test
    fun `impossible content boxes and excessive margin bounds fail explicitly`() {
        assertThrows(IllegalArgumentException::class.java) {
            PdfTableCell(listOf(paragraph("X")), margins = PdfMargins(14_401f, 0f, 0f, 0f))
        }
        val source = PdfTable(listOf(20f), listOf(PdfTableRow(listOf(PdfTableCell(listOf(paragraph("X")), margins = PdfMargins(0f, 10f, 0f, 10f))))))
        assertEquals(PdfFailureCode.CONTENT_OVERFLOW, assertThrows(PdfRenderException::class.java) { paint(source) }.code)
    }

}
