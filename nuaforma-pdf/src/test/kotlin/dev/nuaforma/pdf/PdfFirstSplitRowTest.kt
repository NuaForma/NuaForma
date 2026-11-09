package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PdfFirstSplitRowTest {
    private val style = PdfTextStyle("regular", 10f)
    private val measure = TextMeasurer { text, _ -> TextMetrics(text.length * 5f, 8f, 2f) }
    private val zero = PdfMargins(0f, 0f, 0f, 0f)
    private val page = PdfPage(140f, 160f, PdfMargins(20f, 20f, 20f, 20f))

    private fun paragraph(text: String, height: Float = 14f) = PdfParagraph(listOf(PdfTextRun(text, style)), 0f, 0f,
        PdfParagraphFormat(exactLineHeight = height, widowControl = false))

    private fun table(row: PdfTableRow): PdfTable {
        val header = PdfTableRow(listOf(PdfTableCell(listOf(paragraph("Header")))))
        return PdfTable(listOf(100f), listOf(header, row), cellMargins = zero, border = null, repeatedHeaderRows = 1)
    }

    private fun large(split: Boolean = true) = PdfTableRow(listOf(PdfTableCell(
        (0 until 40).map { paragraph("L${it.toString().padStart(2, '0')}") })), allowSplit = split, splitOversized = true)

    @Test
    fun `a heading and legal first fragment share the first page instead of leaving it nearly empty`() {
        val pages = FlowLayouter.layout(PdfDocument(page, listOf(paragraph("Before"), table(large())), PdfFlow()), measure)
        assertEquals(6, pages.size)
        val first = pages.first().elements.filterIsInstance<PositionedText>().map { it.text }
        assertTrue(first.containsAll(listOf("Before", "Header", "L00")))
        val all = pages.flatMap { it.elements }.filterIsInstance<PositionedText>().map { it.text }
        for (index in 0 until 40) assertEquals(1, all.count { it == "L${index.toString().padStart(2, '0')}" })
        assertEquals(pages.size, all.count { it == "Header" })
    }

    @Test
    fun `an insufficient first fragment moves the whole header to the next page`() {
        val pages = FlowLayouter.layout(PdfDocument(page, listOf(paragraph("Before", 105f), table(large())), PdfFlow()), measure)
        assertEquals(listOf("Before"), pages.first().elements.filterIsInstance<PositionedText>().map { it.text })
        assertTrue(pages[1].elements.filterIsInstance<PositionedText>().map { it.text }.containsAll(listOf("Header", "L00")))
    }

    @Test
    fun `non-splitting rows and active minimum floors retain their original page admission`() {
        val unsplit = FlowLayouter.layout(PdfDocument(page, listOf(paragraph("Before"), table(large(false))), PdfFlow()), measure)
        assertEquals(listOf("Before"), unsplit.first().elements.filterIsInstance<PositionedText>().map { it.text })
        val floor = PdfTableRow(listOf(PdfTableCell(listOf(paragraph("Data")))), allowSplit = true, minimumHeight = 105f)
        val pages = FlowLayouter.layout(PdfDocument(page, listOf(paragraph("Before"), table(floor)), PdfFlow()), measure)
        assertEquals(2, pages.size)
        assertEquals(listOf("Before"), pages.first().elements.filterIsInstance<PositionedText>().map { it.text })
        assertTrue(pages.last().elements.filterIsInstance<PositionedRectangle>().any { it.height == 105f })
    }

}
