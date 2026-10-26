package dev.nuaforma.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PdfParagraphPaginationTest {
    private val page = PdfPage(140f, 124f, PdfMargins(20f, 20f, 20f, 20f))
    private val fonts = FoundationLetter.fonts()
    private fun p(text: String, next: Boolean = false, lines: Boolean = false, widow: Boolean = false, before: Boolean = false) =
        PdfParagraph(listOf(PdfTextRun(text, PdfTextStyle("regular", 10f))), 0f, 0f,
            PdfParagraphFormat(exactLineHeight = 14f, keepWithNext = next, keepLines = lines, widowControl = widow, pageBreakBefore = before))
    private fun tokens(count: Int) = (1..count).joinToString(" ") { "Q%02dWWWWWWWW".format(java.util.Locale.ROOT, it) }
    private fun fillers(count: Int) = (1..count).map { p("Filler $it") }
    private fun pages(blocks: List<PdfBlock>): List<String> = Loader.loadPDF(PdfEngine().render(PdfDocument(page, blocks, PdfFlow()), fonts)).use { pdf ->
        (1..pdf.numberOfPages).map { n -> PDFTextStripper().apply { startPage = n; endPage = n }.getText(pdf) }
    }

    @Test fun `headings and chained headings move with the final paragraph`() {
        for ((count, headings) in listOf(5 to listOf(p("Heading", next = true)),
            4 to listOf(p("Heading", next = true), p("Subheading", next = true)))) {
            val result = pages(fillers(count) + headings + p("Following"))
            assertEquals(2, result.size)
            assertFalse(result[0].contains("Heading"))
            assertTrue(result[1].contains("Heading") && result[1].contains("Following"))
        }
    }

    @Test fun `keep next protects the following start without keeping the entire following paragraph`() {
        val result = pages(fillers(3) + p("Heading", next = true) + p(tokens(4)))
        assertTrue(result[0].contains("Heading") && result[0].contains("Q01") && result[0].contains("Q02"))
        assertFalse(result[0].contains("Q03"))
        assertTrue(result[1].contains("Q03") && result[1].contains("Q04"))
    }

    @Test fun `heading moves with a three line widow protected paragraph instead of being orphaned`() {
        val result = pages(fillers(3) + p("Heading", next = true) + p(tokens(3), widow = true))
        assertFalse(result[0].contains("Heading"))
        assertTrue(result[1].contains("Heading") && result[1].contains("Q01") && result[1].contains("Q03"))
    }

    @Test fun `kept lines move whole while oversized kept paragraphs fail`() {
        val result = pages(fillers(5) + p(tokens(3), lines = true))
        assertEquals(2, result.size)
        assertFalse(result[0].contains("Q01"))
        assertTrue(result[1].contains("Q01") && result[1].contains("Q03"))
        assertEquals(PdfFailureCode.CONTENT_OVERFLOW, assertThrows(PdfRenderException::class.java) {
            PdfEngine().render(PdfDocument(page, listOf(p(tokens(7), lines = true)), PdfFlow()), fonts)
        }.code)
    }

    @Test fun `widow control prevents both single first and single last lines`() {
        val orphan = pages(fillers(5) + p(tokens(4), widow = true))
        assertFalse(orphan[0].contains("Q01")); assertTrue(orphan[1].contains("Q01") && orphan[1].contains("Q04"))
        val widow = pages(fillers(3) + p(tokens(4), widow = true))
        assertTrue(widow[0].contains("Q01") && widow[0].contains("Q02")); assertFalse(widow[0].contains("Q03"))
        assertTrue(widow[1].contains("Q03") && widow[1].contains("Q04"))
        val noPolicy = pages(fillers(3) + p(tokens(4)))
        assertTrue(noPolicy[0].contains("Q03")); assertTrue(noPolicy[1].contains("Q04"))
    }

    @Test fun `explicit breaks override keep next and do not create an initial blank page`() {
        val result = pages(listOf(p("Start", before = true), p("Heading", next = true), p("Break wins", before = true)))
        assertEquals(2, result.size)
        assertTrue(result[0].contains("Start") && result[0].contains("Heading"))
        assertTrue(result[1].contains("Break wins"))
    }

    @Test fun `heading stays with the first atomic table row and with an image`() {
        val table = PdfTable(listOf(90f), listOf(PdfTableRow(listOf(PdfTableCell(listOf(p("Table row")))))))
        val result = pages(fillers(5) + p("Heading", next = true) + table)
        assertFalse(result[0].contains("Heading")); assertTrue(result[1].contains("Heading") && result[1].contains("Table row"))
        val image = PdfImage(FoundationLetter.resource("/fixtures/nuaforma-icon-transparent.png"), 18f, 18f)
        Loader.loadPDF(PdfEngine().render(PdfDocument(page, fillers(5) + p("Heading", next = true) + image, PdfFlow()), fonts)).use { pdf ->
            assertEquals(2, pdf.numberOfPages)
            assertEquals(1, pdf.getPage(1).resources.xObjectNames.count())
        }
    }

    @Test fun `keeps reject fragmented and vertically spanning first rows and frame targets`() {
        val split = PdfTable(listOf(90f), listOf(PdfTableRow(listOf(PdfTableCell(listOf(p("Split")))), allowSplit = true)))
        val merged = PdfTable(listOf(90f), listOf(PdfTableRow(listOf(PdfTableCell(listOf(p("Merged")), rowSpan = 2))), PdfTableRow(emptyList())))
        val frame = PdfTextBox(20f, 20f, 80f, 40f, listOf(p("Frame")), anchor = PdfBoxAnchor.PAGE)
        for (target in listOf(split, merged, frame)) {
            assertEquals(PdfFailureCode.UNSUPPORTED_TEXT, assertThrows(PdfRenderException::class.java) {
                PdfEngine().render(PdfDocument(page, listOf(p("Heading", next = true), target), PdfFlow()), fonts)
            }.code)
        }
    }

    @Test fun `impossible keeps and insufficient widow space fail within budgets`() {
        assertEquals(PdfFailureCode.CONTENT_OVERFLOW, assertThrows(PdfRenderException::class.java) {
            PdfEngine().render(PdfDocument(page, listOf(p(tokens(6), next = true), p("Following")), PdfFlow()), fonts)
        }.code)
        val tiny = PdfPage(140f, 68f, page.margins)
        assertEquals(PdfFailureCode.CONTENT_OVERFLOW, assertThrows(PdfRenderException::class.java) {
            PdfEngine().render(PdfDocument(tiny, listOf(p(tokens(3), widow = true)), PdfFlow()), fonts)
        }.code)
        assertEquals(PdfFailureCode.RESOURCE_LIMIT, assertThrows(PdfRenderException::class.java) {
            PdfEngine().render(PdfDocument(page, fillers(5) + p(tokens(3), lines = true), PdfFlow(maxPages = 1)), fonts)
        }.code)
    }

    @Test fun `page breaks inside frames or bands and keeps beside wrapped frames fail explicitly`() {
        val box = PdfTextBox(20f, 20f, 80f, 40f, listOf(p("Frame", before = true)), anchor = PdfBoxAnchor.PAGE)
        val band = PdfPageBand(0f, 20f, listOf(p("Band", before = true)))
        val wrapped = PdfTextBox(20f, 20f, 80f, 40f, listOf(p("Frame")), anchor = PdfBoxAnchor.PAGE, wrap = PdfBoxWrap.TOP_BOTTOM)
        for ((blocks, flow) in listOf(listOf(box) to PdfFlow(), listOf(p("Body")) to PdfFlow(header = band),
            listOf(wrapped, p("Body", widow = true)) to PdfFlow())) {
            assertEquals(PdfFailureCode.UNSUPPORTED_TEXT, assertThrows(PdfRenderException::class.java) {
                PdfEngine().render(PdfDocument(page, blocks, flow), fonts)
            }.code)
        }
    }

    @Test fun `paragraph keep state is private across concurrent shared engine calls`() {
        val engine = PdfEngine()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = (1..8).map { n -> executor.submit<String> {
                val blocks = fillers(5) + p("Request$n", next = true) + p(tokens(3), lines = true, widow = true)
                Loader.loadPDF(engine.render(PdfDocument(page, blocks, PdfFlow()), fonts)).use { pdf ->
                    assertEquals(2, pdf.numberOfPages)
                    PDFTextStripper().apply { startPage = 2; endPage = 2 }.getText(pdf)
                }
            } }
            results.forEachIndexed { index, result ->
                assertEquals(listOf("Request${index + 1}"), Regex("Request\\d+").findAll(result.get(30, TimeUnit.SECONDS)).map { it.value }.toList())
            }
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)) }
    }
}
