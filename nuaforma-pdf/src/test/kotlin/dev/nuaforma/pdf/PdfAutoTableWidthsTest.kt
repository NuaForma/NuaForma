package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PdfAutoTableWidthsTest {
    private val measurer = TextMeasurer { text, _ -> TextMetrics(text.length * 5f, 8f, 2f) }

    private fun paragraph(text: String) = PdfParagraph(listOf(PdfTextRun(text, PdfTextStyle("regular", 10f))),
        format = PdfParagraphFormat(exactLineHeight = 14f))

    private fun cell(text: String, preference: PdfPreferredWidth? = null, span: Int = 1) =
        PdfTableCell(listOf(paragraph(text)), columnSpan = span, preferredWidth = preference)

    private fun table(cells: List<PdfTableCell>, preference: PdfPreferredWidth? = null, columns: Int = cells.sumOf { it.columnSpan }) =
        PdfTable(List(columns) { 0f }, listOf(PdfTableRow(cells)), widthPolicy = PdfTableWidthPolicy.AUTO_FIT, preferredWidth = preference)

    @Test
    fun `table percentages use available content width and cell percentages use the resulting table width`() {
        val source = table(listOf(cell("A", PdfPreferredWidth.percent(25f)), cell("B", PdfPreferredWidth.percent(75f))), PdfPreferredWidth.percent(80f))
        val widths = TableWidthResolver.resolve(source, measurer, 400f).columnWidths
        assertEquals(320f, widths.sum(), .01f)
        assertEquals(80f, widths[0], .01f)
        assertEquals(240f, widths[1], .01f)
        val smaller = TableWidthResolver.resolve(source, measurer, 250f).columnWidths
        assertEquals(200f, smaller.sum(), .01f)
        assertEquals(50f, smaller[0], .01f)
        assertEquals(listOf(0f, 0f), source.columnWidths)
    }

    @Test
    fun `preferred physical widths wrap breakable content instead of overflowing the page`() {
        val source = table(listOf(cell("alpha beta gamma delta epsilon", PdfPreferredWidth.points(240f)), cell("END", PdfPreferredWidth.points(120f))))
        val measured = TableLayouter.measure(source, measurer, 150f)
        assertEquals(150f, measured.table.columnWidths.sum(), .01f)
        assertTrue(measured.cells.first().pieces.size > 1)
        assertTrue(measured.table.columnWidths.all { it > 12f })
    }

    @Test
    fun `merged cell text constrains the shared columns without changing the grid`() {
        val source = PdfTable(listOf(0f, 0f), listOf(PdfTableRow(listOf(cell("A"), cell("B"))),
            PdfTableRow(listOf(cell("LONGWORD", span = 2)))), widthPolicy = PdfTableWidthPolicy.AUTO_FIT)
        val result = TableLayouter.measure(source, measurer, 150f)
        assertTrue(result.table.columnWidths.sum() >= 52f)
        assertEquals(3, result.cells.size)
        assertEquals(1, result.cells.last().pieces.size)
    }

    @Test
    fun `unbreakable content and nested fixed tables retain explicit overflow`() {
        val long = table(listOf(cell("X".repeat(50))))
        assertEquals(PdfFailureCode.CONTENT_OVERFLOW, assertThrows(PdfRenderException::class.java) {
            TableLayouter.measure(long, measurer, 100f)
        }.code)
        val nested = PdfTable(listOf(200f), listOf(PdfTableRow(listOf(cell("NESTED")))))
        val outer = PdfTable(listOf(0f), listOf(PdfTableRow(listOf(PdfTableCell(listOf(nested))))), widthPolicy = PdfTableWidthPolicy.AUTO_FIT)
        assertThrows(PdfRenderException::class.java) { TableLayouter.measure(outer, measurer, 150f) }
    }

    @Test
    fun `immutable document transformations retain both preferences`() {
        val source = table(listOf(cell("A", PdfPreferredWidth.points(70f)), cell("B")), PdfPreferredWidth.percent(60f))
        val page = PdfPage(300f, 300f, PdfMargins(30f, 30f, 30f, 30f))
        val result = DocumentTransforms.normalized(PdfDocument(page, listOf(source), PdfFlow())).blocks.single() as PdfTable
        assertEquals(source.preferredWidth, result.preferredWidth)
        assertEquals(source.rows[0].cells[0].preferredWidth, result.rows[0].cells[0].preferredWidth)
        assertEquals(PdfTableWidthPolicy.AUTO_FIT, result.widthPolicy)
    }

    @Test
    fun `nested automatic percentages use parent cell content width`() {
        val child = table(listOf(cell("CHILD")), PdfPreferredWidth.percent(80f))
        val parent = PdfTable(listOf(200f), listOf(PdfTableRow(listOf(PdfTableCell(listOf(child))))))
        val layout = TableLayouter.measure(parent, measurer, 240f)
        val frame = layout.cells.single().pieces.single().elements.filterIsInstance<PositionedRectangle>().single()
        assertEquals((200f - 12f) * .8f, frame.width, .01f)
    }

    @Test
    fun `very long breakable text wraps and is not treated as an oversized unbreakable string`() {
        val source = table(listOf(cell("word ".repeat(800))))
        val result = TableLayouter.measure(source, measurer, 120f)
        assertEquals(120f, result.table.columnWidths.sum(), .01f)
        assertTrue(result.cells.single().pieces.size > 100)
    }

    @Test
    fun `one immutable table resolves independently for simultaneous containing widths`() {
        val source = table(listOf(cell("A", PdfPreferredWidth.percent(25f)), cell("B", PdfPreferredWidth.percent(75f))), PdfPreferredWidth.percent(80f))
        val pool = Executors.newFixedThreadPool(3)
        try {
            val widths = listOf(200f, 300f, 400f, 250f, 350f, 450f)
            val jobs = widths.map { width -> pool.submit<List<Float>> { TableWidthResolver.resolve(source, measurer, width).columnWidths } }
            for ((index, job) in jobs.withIndex()) {
                val actual = job.get(30, TimeUnit.SECONDS)
                assertEquals(widths[index] * .8f, actual.sum(), .01f)
                assertEquals(widths[index] * .2f, actual.first(), .01f)
            }
            assertEquals(listOf(0f, 0f), source.columnWidths)
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `actual PDF output retains wrapped text in body and percentage header table`() {
        val content = table(listOf(cell("A sequence of short words that wraps in a narrow column", PdfPreferredWidth.percent(60f)), cell("FINAL", PdfPreferredWidth.percent(40f))), PdfPreferredWidth.percent(100f))
        val header = table(listOf(cell("HEADER_LEFT", PdfPreferredWidth.percent(50f)), cell("HEADER_RIGHT", PdfPreferredWidth.percent(50f))), PdfPreferredWidth.percent(80f))
        val document = PdfDocument(PdfPage(300f, 400f, PdfMargins(100f, 30f, 30f, 30f)), listOf(content),
            PdfFlow(header = PdfPageBand(10f, 70f, listOf(header))))
        Loader.loadPDF(PdfEngine().render(document, FoundationLetter.fonts())).use { pdf ->
            val text = PDFTextStripper().getText(pdf).replace(Regex("\\s+"), " ")
            for (value in listOf("HEADER_LEFT", "HEADER_RIGHT", "A sequence of short words that wraps in a narrow column", "FINAL")) assertTrue(text.contains(value), text)
            assertEquals(1, pdf.numberOfPages)
            assertTrue(pdf.getPage(0).resources.fontNames.all { pdf.getPage(0).resources.getFont(it).isEmbedded })
        }
    }

    @Test
    fun `invalid preference values reject and zero preferences remain bounded`() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f)) {
            assertThrows(IllegalArgumentException::class.java) { PdfPreferredWidth.points(value) }
            assertThrows(IllegalArgumentException::class.java) { PdfPreferredWidth.percent(value) }
        }
        assertThrows(IllegalArgumentException::class.java) { PdfPreferredWidth.percent(101f) }
        assertThrows(IllegalArgumentException::class.java) { PdfPreferredWidth.points(14_401f) }
        assertEquals(PdfPreferredWidth.percent(0f), PdfPreferredWidth.percent(-0f))
        val widths = TableWidthResolver.resolve(table(listOf(cell(" ", PdfPreferredWidth.points(0f)))), measurer, 100f).columnWidths
        assertTrue(widths.single() > 12f)
    }

}
