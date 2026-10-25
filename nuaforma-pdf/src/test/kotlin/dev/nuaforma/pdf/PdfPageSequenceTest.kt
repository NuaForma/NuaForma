package dev.nuaforma.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PdfPageSequenceTest {
    private val style = PdfTextStyle("regular", 10f)
    private val small = PdfPage(240f, 180f, PdfMargins(30f, 20f, 30f, 20f))
    private val wide = PdfPage(360f, 180f, PdfMargins(30f, 25f, 30f, 35f))
    private fun p(text: String) = PdfParagraph(listOf(PdfTextRun(text, style)), 0f, 0f, PdfParagraphFormat(exactLineHeight = 14f))
    private fun flow() = PdfFlow(footer = PdfPageBand(8f, 20f, listOf(PdfParagraph(
        listOf(PdfTextRun("Page ", style), PdfPageNumber(style)), 0f, 0f, PdfParagraphFormat(exactLineHeight = 14f)))))

    @Test fun `mixed physical policies preserve body ranges and global page fields`() {
        val sequences = listOf(PdfPageSequence(small, listOf(p("First range")), flow()),
            PdfPageSequence(wide, (1..16).map { p("Wide item $it") }, flow()),
            PdfPageSequence(small, listOf(p("Last range")), flow()))
        Loader.loadPDF(PdfEngine().render(PdfDocument(sequences), FoundationLetter.fonts())).use { pdf ->
            assertEquals(4, pdf.numberOfPages)
            for ((i, width) in listOf(240f, 360f, 360f, 240f).withIndex()) {
                assertEquals(width, pdf.getPage(i).mediaBox.width)
                assertEquals(0, pdf.getPage(i).rotation)
                val text = PDFTextStripper().apply { startPage = i + 1; endPage = i + 1 }.getText(pdf)
                assertTrue(text.contains("Page ${i + 1}"))
                assertEquals(i == 0, text.contains("First range"))
                assertEquals(i == 3, text.contains("Last range"))
            }
            val body = PDFTextStripper().getText(pdf)
            for (n in 1..16) assertEquals(1, Regex("Wide item $n\\b").findAll(body).count())
        }
        val many = List(11) { PdfPageSequence(small, listOf(p("Range ${it + 1}")), flow()) }
        Loader.loadPDF(PdfEngine().render(PdfDocument(many), FoundationLetter.fonts())).use { pdf ->
            assertEquals(11, pdf.numberOfPages)
            assertTrue(PDFTextStripper().apply { startPage = 11; endPage = 11 }.getText(pdf).contains("Page 11"))
        }
    }

    @Test fun `page and text budgets span every sequence rather than reset at transitions`() {
        val twoPages = PdfPageSequence(small, (1..16).map { p("Item $it") }, flow())
        val document = PdfDocument(listOf(twoPages, twoPages), 3)
        assertEquals(PdfFailureCode.RESOURCE_LIMIT, assertThrows(PdfRenderException::class.java) {
            PdfEngine().render(document, FoundationLetter.fonts())
        }.code)
        assertThrows(IllegalArgumentException::class.java) { PdfDocument(listOf(twoPages, twoPages), 1) }
        assertThrows(IllegalArgumentException::class.java) { PdfDocument(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { PdfDocument(List(33) { twoPages }) }
        assertThrows(IllegalArgumentException::class.java) { PdfPageSequence(small, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            PdfDocument(List(2) { PdfPageSequence(small, listOf(p("x".repeat(50_001)))) })
        }
        assertThrows(IllegalArgumentException::class.java) {
            PdfDocument(List(2) { PdfPageSequence(small, List(501) { p("x") }) })
        }
        val fakeImage = PdfImage(ByteArray(4_000_000), 10f, 10f)
        val largeImages = PdfDocument(List(5) { PdfPageSequence(small, listOf(fakeImage)) })
        assertThrows(IllegalArgumentException::class.java) { PdfEngine().render(largeImages, FoundationLetter.fonts()) }
    }

    @Test fun `sequence collections are defensive and late ranges validate their resources`() {
        val blocks = mutableListOf<PdfBlock>(p("Retained"))
        val sequences = mutableListOf(PdfPageSequence(small, blocks))
        val document = PdfDocument(sequences)
        blocks.clear(); sequences.clear()
        assertEquals(1, document.pageSequences.single().blocks.size)
        assertThrows(UnsupportedOperationException::class.java) { (document.pageSequences as MutableList<PdfPageSequence>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (document.pageSequences[0].blocks as MutableList<PdfBlock>).clear() }
        val missing = PdfParagraph(listOf(PdfTextRun("Later", PdfTextStyle("absent", 10f))))
        assertEquals(PdfFailureCode.FONT_UNAVAILABLE, assertThrows(PdfRenderException::class.java) {
            PdfEngine().render(PdfDocument(listOf(document.pageSequences.single(), PdfPageSequence(wide, listOf(missing)))), FoundationLetter.fonts())
        }.code)
    }

    @Test fun `one engine keeps mixed sequences and numbering request local under concurrency`() {
        val engine = PdfEngine(); val fonts = FoundationLetter.fonts(); val pool = Executors.newFixedThreadPool(4)
        try {
            val jobs = (0..7).map { id -> pool.submit {
                val sizes = if (id % 2 == 0) listOf(small, wide) else listOf(wide, small)
                val document = PdfDocument(sizes.map { PdfPageSequence(it, listOf(p("Job $id")), flow()) })
                Loader.loadPDF(engine.render(document, fonts)).use { pdf ->
                    assertEquals(sizes.map { it.width }, pdf.pages.map { it.mediaBox.width })
                    val text = PDFTextStripper().getText(pdf)
                    assertTrue(text.contains("Page 1") && text.contains("Page 2"))
                    assertTrue(text.contains("Job $id"))
                    for (other in 0..7) if (other != id) assertFalse(text.contains("Job $other"))
                }
            } }
            jobs.forEach { it.get(60, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)) }
    }
}
