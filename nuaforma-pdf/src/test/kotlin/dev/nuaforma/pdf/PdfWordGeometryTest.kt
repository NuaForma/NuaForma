package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PdfWordGeometryTest {
    private val style = PdfTextStyle("regular", 10f)

    private fun p(text: String) = PdfParagraph(listOf(PdfTextRun(text, style)), 0f, 0f, PdfParagraphFormat(exactLineHeight = 14f))

    @Test
    fun `measured footer reserves real content when its offset exceeds the original bottom margin`() {
        val page = PdfPage(240f, 180f, PdfMargins(20f, 20f, 10f, 20f))
        val footer = PdfPageBand(24f, 80f, listOf(p("FOOTER"))).withContentReservation()
        val data = PdfEngine().render(PdfDocument(page, List(12) { p("BODY-$it") }, PdfFlow(footer = footer)), FoundationLetter.fonts())
        Loader.loadPDF(data).use { pdf ->
            assertTrue(pdf.numberOfPages >= 2)
            for (n in 1..pdf.numberOfPages) assertTrue(PDFTextStripper().apply { startPage = n; endPage = n }.getText(pdf).contains("FOOTER"))
            assertTrue(PDFTextStripper().getText(pdf).contains("BODY-11"))
        }
    }

    @Test
    fun `signed indents enter physical margins and off-page frames are rejected`() {
        val page = PdfPage(240f, 180f, PdfMargins(20f, 20f, 20f, 20f))
        val text = p("SIGNED").copy(format = PdfParagraphFormat(leftIndent = -8f, rightIndent = -8f, firstLineIndent = -4f, exactLineHeight = 14f))
        Loader.loadPDF(PdfEngine().render(PdfDocument(page, listOf(text), PdfFlow()), FoundationLetter.fonts())).use {
            assertTrue(PDFTextStripper().getText(it).contains("SIGNED"))
        }
        val outside = text.copy(format = text.format.copy(leftIndent = -22f))
        val failure = assertThrows(PdfRenderException::class.java) { PdfEngine().render(PdfDocument(page, listOf(outside), PdfFlow()), FoundationLetter.fonts()) }
        assertEquals(PdfFailureCode.CONTENT_OVERFLOW, failure.code)
    }

    @Test
    fun `growing page bands retain footnotes and keep them above the footer`() {
        val page = PdfPage(280f, 260f, PdfMargins(12f, 24f, 12f, 24f))
        val marker = style.copy(size = 6f, baselineShift = 3f)
        val note = PdfNote("note", PdfNoteKind.FOOTNOTE, PdfListValue("notes", 0, 1, "1"),
            listOf(PdfParagraph(listOf(PdfNoteLabel(marker), PdfTextRun(" FOOTNOTE", style)), 0f, 0f, PdfParagraphFormat(exactLineHeight = 14f))),
            PdfNoteSeparator(width = 80f))
        val owner = PdfParagraph(listOf(PdfTextRun("OWNER", style), PdfNoteCall("call", "note", marker)), 0f, 0f, PdfParagraphFormat(exactLineHeight = 14f))
        val policy = PdfFlow(PdfPageBand(20f, 100f, listOf(p("HEADER"))).withContentReservation(),
            PdfPageBand(24f, 100f, listOf(p("FOOTER"))).withContentReservation())
        val document = PdfDocument(page, listOf(owner) + List(18) { p("BODY-$it") }, policy, notes = listOf(note))
        val measure = TextMeasurer { text, face -> TextMetrics(text.length * face.size / 2, face.size * .8f + face.baselineShift, face.size * .2f - face.baselineShift) }
        val layout = ReferenceLayouter.layout(document, measure, PdfRenderControl())
        val first = DocumentLayouter.texts(layout.pages.first().elements).toList()
        assertTrue(first.single { it.text == "HEADER" }.baseline < first.single { it.text == "OWNER" }.baseline)
        assertTrue(first.single { it.text == "OWNER" }.baseline < first.single { it.text == "FOOTNOTE" }.baseline)
        assertTrue(first.single { it.text == "FOOTNOTE" }.baseline < first.single { it.text == "FOOTER" }.baseline)
        Loader.loadPDF(PdfEngine().render(document, FoundationLetter.fonts())).use { pdf ->
            val text = PDFTextStripper().getText(pdf)
            assertEquals(1, Regex("FOOTNOTE").findAll(text).count())
            assertEquals(pdf.numberOfPages, Regex("FOOTER").findAll(text).count())
            assertTrue(text.contains("BODY-17"))
        }
    }

    @Test
    fun `minimum line style is immutable across paragraph copies and does not recolor text`() {
        val minimum = PdfTextStyle("bold", 18f, PdfColor(255, 0, 0))
        val paragraph = PdfParagraph(listOf(PdfTextRun("MARK", style)), format = PdfParagraphFormat(automaticLineSpacing = PdfAutomaticLineSpacing()))
            .withMinimumLineStyle(minimum)
        assertSame(minimum, paragraph.copy().withSpacing(PdfParagraphSpacing()).minimumLineStyle)
        assertEquals(PdfColor(0, 0, 0), (paragraph.runs.single() as PdfTextRun).style.color)
        val page = PdfPage(240f, 180f, PdfMargins(20f, 20f, 20f, 20f))
        assertDoesNotThrow { PdfEngine().render(PdfDocument(page, listOf(paragraph)), FoundationLetter.fonts()) }
    }
}
