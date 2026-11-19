package dev.nuaforma.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PdfListPageBreakTest {
    private val style = PdfTextStyle("regular", 12f)
    private val page = PdfPage(300f, 300f, PdfMargins(30f, 30f, 30f, 30f))

    private fun paragraph(runs: List<PdfInline>) = PdfParagraph(runs, 0f, 0f,
        PdfParagraphFormat(leftIndent = 60f, firstLineIndent = -24f, exactLineHeight = 20f, widowControl = false),
        PdfListLabel(PdfListValue("list", 0, 1, "1."), style))

    @Test
    fun `leading and consecutive page boundaries move the list marker with its first content`() {
        val p = paragraph(listOf(PdfPageBreak(style), PdfPageBreak(style), PdfTextRun("BODY", style)))
        Loader.loadPDF(PdfEngine().render(PdfDocument(page, listOf(p), PdfFlow()), FoundationLetter.fonts())).use { pdf ->
            assertEquals(3, pdf.numberOfPages)
            for (number in 1..3) {
                val text = PDFTextStripper().apply { startPage = number; endPage = number }.getText(pdf).replace(Regex("\\s+"), "")
                assertEquals(if (number == 3) "1.BODY" else "", text)
            }
        }
        assertEquals(1, p.listLabel!!.value.ordinal)
        assertEquals(3, p.runs.size)
    }

    @Test
    fun `continuation after a page break does not repeat or increment a list marker`() {
        val p = paragraph(listOf(PdfTextRun("BEFORE", style), PdfPageBreak(style), PdfTextRun("AFTER", style)))
        Loader.loadPDF(PdfEngine().render(PdfDocument(page, listOf(p), PdfFlow()), FoundationLetter.fonts())).use { pdf ->
            assertEquals(2, pdf.numberOfPages)
            val first = PDFTextStripper().apply { endPage = 1 }.getText(pdf).replace(Regex("\\s+"), "")
            val second = PDFTextStripper().apply { startPage = 2 }.getText(pdf).replace(Regex("\\s+"), "")
            assertEquals("1.BEFORE", first)
            assertEquals("AFTER", second)
        }
    }

    @Test
    fun `an ending break does not create an unused final page or carry paragraph spacing onto a new page`() {
        val ending = paragraph(listOf(PdfTextRun("BEFORE", style), PdfPageBreak(style))).copy(spaceAfter = 18f)
        val following = PdfParagraph(listOf(PdfTextRun("AFTER", style)), 0f, 0f,
            PdfParagraphFormat(exactLineHeight = 20f, widowControl = false))
        Loader.loadPDF(PdfEngine().render(PdfDocument(page, listOf(ending), PdfFlow(maxPages = 1)), FoundationLetter.fonts())).use { pdf ->
            assertEquals(1, pdf.numberOfPages)
        }
        val positions = mutableListOf<Float>()
        Loader.loadPDF(PdfEngine().render(PdfDocument(page, listOf(ending, following), PdfFlow(maxPages = 2)), FoundationLetter.fonts())).use { pdf ->
            assertEquals(2, pdf.numberOfPages)
            object : PDFTextStripper() {

                override fun processTextPosition(text: org.apache.pdfbox.text.TextPosition) {
                    if (currentPageNo == 2) positions += text.yDirAdj
                    super.processTextPosition(text)
                }

            }.getText(pdf)
        }
        assertTrue(positions.isNotEmpty())
        assertTrue(positions.all { kotlin.math.abs(it - 46f) < .001f })
        val threePages = paragraph(listOf(PdfPageBreak(style), PdfPageBreak(style), PdfPageBreak(style)))
        assertEquals(PdfFailureCode.RESOURCE_LIMIT, assertThrows(PdfRenderException::class.java) {
            PdfEngine().render(PdfDocument(page, listOf(threePages), PdfFlow(maxPages = 2)), FoundationLetter.fonts())
        }.code)
    }

}
