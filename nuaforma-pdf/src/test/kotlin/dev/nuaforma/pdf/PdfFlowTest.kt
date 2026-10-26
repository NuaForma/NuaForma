package dev.nuaforma.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PdfFlowTest {
    private val style = PdfTextStyle("regular", 10f)
    private fun paragraph(text: String) = PdfParagraph(listOf(PdfTextRun(text, style)), 0f, 0f, PdfParagraphFormat(exactLineHeight = 14f))
    private val page = PdfPage(240f, 180f, PdfMargins(30f, 20f, 30f, 20f))

    @Test fun `flow splits paragraphs without empty trailing pages and enforces page limits`() {
        val blocks = (1..16).map { paragraph("Entry $it") }
        Loader.loadPDF(PdfEngine().render(PdfDocument(page, blocks, PdfFlow()), FoundationLetter.fonts())).use { pdf ->
            assertEquals(2, pdf.numberOfPages)
            val text = PDFTextStripper().getText(pdf)
            assertTrue(text.contains("Entry 1")); assertTrue(text.contains("Entry 16"))
        }
        val error = assertThrows(PdfRenderException::class.java) { PdfEngine().render(PdfDocument(page, blocks, PdfFlow(maxPages = 1)), FoundationLetter.fonts()) }
        assertEquals(PdfFailureCode.RESOURCE_LIMIT, error.code)
        assertEquals(PdfFailureCode.CONTENT_OVERFLOW, assertThrows(PdfRenderException::class.java) { PdfEngine().render(PdfDocument(page, blocks), FoundationLetter.fonts()) }.code)
    }

    @Test fun `oversized table rows and overlapping bands reject rather than split clip or loop`() {
        val cell = PdfTableCell(listOf(paragraph("row ".repeat(100))))
        val table = PdfTable(listOf(180f), listOf(PdfTableRow(listOf(cell))))
        assertEquals(PdfFailureCode.CONTENT_OVERFLOW, assertThrows(PdfRenderException::class.java) { PdfEngine().render(PdfDocument(page, listOf(table), PdfFlow()), FoundationLetter.fonts()) }.code)
        val band = PdfPageBand(20f, 20f, listOf(paragraph("header")))
        assertEquals(PdfFailureCode.CONTENT_OVERFLOW, assertThrows(PdfRenderException::class.java) { PdfEngine().render(PdfDocument(page, listOf(paragraph("body")), PdfFlow(header = band)), FoundationLetter.fonts()) }.code)
    }

    @Test fun `table collections are defensive and repeated headers cannot consume every row`() {
        val cells = mutableListOf(PdfTableCell(listOf(paragraph("content"))))
        val row = PdfTableRow(cells)
        cells.clear()
        val rows = mutableListOf(row)
        val widths = mutableListOf(100f)
        val table = PdfTable(widths, rows)
        widths.clear(); rows.clear()
        assertEquals(1, table.rows.single().cells.size)
        assertEquals(100f, table.columnWidths.single())
        assertThrows(IllegalArgumentException::class.java) { PdfTable(listOf(100f), listOf(row), repeatedHeaderRows = 1) }
        val sharedCell = PdfTableCell(List(16) { paragraph("x") })
        val sharedRow = PdfTableRow(List(16) { sharedCell })
        val oversized = PdfTable(List(16) { 20f }, List(500) { sharedRow })
        assertTrue(assertThrows(IllegalArgumentException::class.java) { PdfDocument(page, listOf(oversized), PdfFlow()) }.message!!.contains("Paragraph budget"))
    }

    @Test fun `invalid span coverage and mixed fragmentation fail before rendering`() {
        val p = paragraph("x")
        assertThrows(IllegalArgumentException::class.java) { PdfTable(listOf(90f), listOf(PdfTableRow(listOf(PdfTableCell(listOf(p), rowSpan = 2))))) }
        assertThrows(IllegalArgumentException::class.java) { PdfTable(listOf(90f, 90f), listOf(PdfTableRow(listOf(PdfTableCell(listOf(p)))))) }
        val spanning = PdfTableRow(listOf(PdfTableCell(listOf(p), rowSpan = 2)))
        assertThrows(IllegalArgumentException::class.java) { PdfTable(listOf(90f), listOf(spanning, PdfTableRow(emptyList(), allowSplit = true))) }
        assertThrows(IllegalArgumentException::class.java) { PdfTable(listOf(90f), listOf(spanning, PdfTableRow(emptyList())), repeatedHeaderRows = 1) }
        fun wrap(block: PdfBlock) = PdfTable(listOf(180f), listOf(PdfTableRow(listOf(PdfTableCell(listOf(block))))))
        assertThrows(IllegalArgumentException::class.java) { PdfDocument(page, listOf(wrap(wrap(wrap(p)))), PdfFlow()) }
    }

    @Test fun `oversized preservation is opt in and fragmentation respects page budget`() {
        val cell = PdfTableCell((1..30).map { paragraph("Line $it") })
        val table = PdfTable(listOf(180f), listOf(PdfTableRow(listOf(cell), splitOversized = true)))
        Loader.loadPDF(PdfEngine().render(PdfDocument(page, listOf(table), PdfFlow()), FoundationLetter.fonts())).use { pdf ->
            assertTrue(pdf.numberOfPages > 1)
            val text = PDFTextStripper().getText(pdf)
            (1..30).forEach { assertEquals(1, Regex("Line $it\\b").findAll(text).count()) }
        }
        val error = assertThrows(PdfRenderException::class.java) { PdfEngine().render(PdfDocument(page, listOf(table), PdfFlow(maxPages = 1)), FoundationLetter.fonts()) }
        assertEquals(PdfFailureCode.RESOURCE_LIMIT, error.code)
    }
}
