package dev.nuaforma.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PdfPageFieldsTest {
    private val style = PdfTextStyle("regular", 10f)
    private val page = PdfPage(240f, 180f, PdfMargins(30f, 20f, 30f, 20f))
    private fun p(runs: List<PdfInline>) = PdfParagraph(runs, 0f, 0f, PdfParagraphFormat(exactLineHeight = 14f))
    private fun body(count: Int) = List(count) { p(listOf(PdfTextRun("Body ${it + 1}", style))) }
    private fun fields(label: String) = p(listOf(PdfTextRun("$label ", style), PdfPageNumber(style), PdfTextRun(" of ", style), PdfPageCount(style)))
    private fun band(label: String) = PdfPageBand(8f, 20f, listOf(fields(label)))
    private fun text(pdf: org.apache.pdfbox.pdmodel.PDDocument, number: Int) = PDFTextStripper().apply { startPage = number; endPage = number }.getText(pdf)

    @Test fun `global final count resolves after all ranges in first even odd and table bands`() {
        val table = PdfTable(listOf(200f), listOf(PdfTableRow(listOf(PdfTableCell(listOf(fields("TABLE")))))), PdfMargins(0f, 0f, 0f, 0f))
        val footer = PdfPageBand(8f, 20f, listOf(table))
        val policy = PdfFlow(band("ODD"), footer, firstPageBands = PdfPageBands(band("FIRST"), footer), evenPageBands = PdfPageBands(band("EVEN"), footer))
        val model = PdfDocument(listOf(240f, 300f, 260f).map { width -> PdfPageSequence(page.copy(width = width), body(32), policy) })
        Loader.loadPDF(PdfEngine().render(model, FoundationLetter.fonts())).use { pdf ->
            assertEquals(12, pdf.numberOfPages)
            for (number in 1..12) {
                val label = if (number in listOf(1, 5, 9)) "FIRST" else if (number % 2 == 0) "EVEN" else "ODD"
                assertTrue(text(pdf, number).contains("$label $number of 12"))
                assertTrue(text(pdf, number).contains("TABLE $number of 12"))
            }
        }
    }

    @Test fun `single page ranges before and after flowing ranges contribute to the total`() {
        val model = PdfDocument(listOf(PdfPageSequence(page, body(1)), PdfPageSequence(page, body(24), PdfFlow(header = band("FLOW"))), PdfPageSequence(page, body(1))))
        Loader.loadPDF(PdfEngine().render(model, FoundationLetter.fonts())).use { pdf ->
            assertEquals(5, pdf.numberOfPages)
            for (number in 2..4) assertTrue(text(pdf, number).contains("FLOW $number of 5"))
        }
    }

    @Test fun `actual two digit count remeasures bands and rejects overflow without changing body pagination`() {
        val narrow = page.copy(width = 73f)
        val count = p(listOf(PdfTextRun("Total ", style), PdfPageCount(style)))
        val flow = PdfFlow(header = PdfPageBand(8f, 14f, listOf(count)))
        fun document(pages: Int) = PdfDocument(narrow, List(pages * 8) { p(listOf(PdfTextRun("B", style))) }, flow)
        Loader.loadPDF(PdfEngine().render(document(9), FoundationLetter.fonts())).use { pdf ->
            assertEquals(9, pdf.numberOfPages); assertTrue(text(pdf, 1).contains("Total 9"))
        }
        assertEquals(PdfFailureCode.CONTENT_OVERFLOW, assertThrows(PdfRenderException::class.java) { PdfEngine().render(document(10), FoundationLetter.fonts()) }.code)
    }

    @Test fun `unresolved fields reject in body tables frames and aggregate field text is bounded`() {
        val field = fields("BODY")
        val table = PdfTable(listOf(200f), listOf(PdfTableRow(listOf(PdfTableCell(listOf(field))))))
        val frame = PdfTextBox(20f, 30f, 180f, 40f, listOf(field))
        for (block in listOf<PdfBlock>(field, table, frame)) {
            assertEquals(PdfFailureCode.UNSUPPORTED_TEXT, assertThrows(PdfRenderException::class.java) { PdfEngine().render(PdfDocument(page, listOf(block), PdfFlow()), FoundationLetter.fonts()) }.code)
        }
        val large = p(List(1_000) { PdfPageCount(style) })
        assertThrows(IllegalArgumentException::class.java) { PdfDocument(page, List(51) { large }, PdfFlow()) }
        val missing = PdfPageBand(8f, 20f, listOf(p(listOf(PdfPageCount(style.copy(fontId = "absent"))))))
        assertEquals(PdfFailureCode.FONT_UNAVAILABLE, assertThrows(PdfRenderException::class.java) { PdfEngine().render(PdfDocument(page, body(1), PdfFlow(footer = missing)), FoundationLetter.fonts()) }.code)
    }

    @Test fun `concurrent requests retain their own final count on a shared engine`() {
        val engine = PdfEngine(); val fonts = FoundationLetter.fonts(); val pool = Executors.newFixedThreadPool(4)
        try {
            val jobs = (3..8).map { count -> pool.submit {
                Loader.loadPDF(engine.render(PdfDocument(page, body(count * 8), PdfFlow(header = band("JOB-$count"))), fonts)).use { pdf ->
                    assertEquals(count, pdf.numberOfPages)
                    for (number in 1..count) assertTrue(text(pdf, number).contains("JOB-$count $number of $count"))
                }
            } }
            jobs.forEach { it.get(60, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)) }
    }
}
