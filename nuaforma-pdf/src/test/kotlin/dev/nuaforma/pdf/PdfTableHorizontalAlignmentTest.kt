package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PdfTableHorizontalAlignmentTest {
    private val style = PdfTextStyle("regular", 12f)
    private val page = PdfPage(500f, 350f, PdfMargins(30f, 40f, 30f, 40f))
    private val measure = TextMeasurer { value, style -> TextMetrics(value.length * style.size / 2, style.size * .8f, style.size * .2f) }

    private fun paragraph(value: String) = PdfParagraph(listOf(PdfTextRun(value, style)), 0f, 0f,
        PdfParagraphFormat(exactLineHeight = 16f))

    private fun table(indent: Float = 0f, policy: PdfTableWidthPolicy = PdfTableWidthPolicy.FIXED,
                      preferred: PdfPreferredWidth? = null) = PdfTable(listOf(80f, 120f), listOf(PdfTableRow(listOf(
        PdfTableCell(listOf(paragraph("MARK"))), PdfTableCell(listOf(paragraph("VALUE")))))), leftIndent = indent,
        widthPolicy = policy, preferredWidth = preferred)

    private fun x(document: PdfDocument): Float = DocumentLayouter.texts(
        ReferenceLayouter.layout(document, measure, PdfRenderControl()).pages.single().elements)
        .single { it.text == "MARK" }.x

    @Test
    fun `alignment resolves physical origins while overflow stays an allowance`() {
        val source = table(12f).withRightOverflow(20f)
        val expected = mapOf(PdfTableAlignment.LEFT to 58f, PdfTableAlignment.CENTER to 168f, PdfTableAlignment.RIGHT to 277.75f)
        for ((alignment, target) in expected) {
            val value = source.withAlignment(alignment)
            assertEquals(target, x(PdfDocument(page, listOf(value), PdfFlow())), .001f, alignment.name)
        }
        val aligned = table().withAlignment(PdfTableAlignment.RIGHT)
        assertEquals(x(PdfDocument(page, listOf(aligned), PdfFlow())),
            x(PdfDocument(page, listOf(aligned.withRightOverflow(20f)), PdfFlow())), .001f)
        assertEquals(PdfTableAlignment.LEFT, source.alignment)
        assertEquals(PdfTableAlignment.RIGHT, aligned.withRightOverflow(20f).alignment)
    }

    @Test
    fun `natural and automatic widths resolve before horizontal alignment`() {
        val natural = table(policy = PdfTableWidthPolicy.FIT_CONTENTS).withAlignment(PdfTableAlignment.CENTER)
        val measured = TableLayouter.measure(natural, measure, 420f).table
        assertEquals(78f, measured.columnWidths.sum(), .001f)
        assertEquals((420f - 78f) / 2, measured.leftIndent, .001f)
        val automatic = table(policy = PdfTableWidthPolicy.AUTO_FIT, preferred = PdfPreferredWidth.points(180f))
            .withAlignment(PdfTableAlignment.CENTER)
        val fitted = TableLayouter.measure(automatic, measure, 420f).table
        assertEquals(180f, fitted.columnWidths.sum(), .001f)
        assertEquals(120f, fitted.leftIndent, .001f)
    }

    @Test
    fun `page bands and nested tables preserve alignment through semantic and field copies`() {
        val aligned = table().withAlignment(PdfTableAlignment.RIGHT)
        val band = PdfPageBand(5f, 80f, listOf(aligned))
        val bandPage = page.copy(margins = PdfMargins(100f, 40f, 30f, 40f))
        assertEquals(265.75f, x(PdfDocument(bandPage, listOf(paragraph("body")), PdfFlow(header = band))), .001f)
        val outer = PdfTable(listOf(400f), listOf(PdfTableRow(listOf(PdfTableCell(listOf(aligned))))))
        assertEquals(239.75f, x(PdfDocument(page, listOf(outer), PdfFlow())), .001f)
    }

    @Test
    fun `note pagination keeps the resolved table origin`() {
        val marker = style.copy(size = 6f, baselineShift = 3f)
        val note = PdfNote("foot", PdfNoteKind.FOOTNOTE, PdfListValue("notes", 0, 1, "1"), listOf(
            PdfParagraph(listOf(PdfNoteLabel(marker), PdfTextRun(" Note", style)), 0f, 0f,
                PdfParagraphFormat(exactLineHeight = 16f))))
        val call = PdfParagraph(listOf(PdfTextRun("Call", style), PdfNoteCall("call", "foot", marker)), 0f, 0f,
            PdfParagraphFormat(exactLineHeight = 16f))
        val document = PdfDocument(page, listOf(table().withAlignment(PdfTableAlignment.RIGHT), call), PdfFlow(), notes = listOf(note))
        assertEquals(265.75f, x(document), .001f)
    }

    @Test
    fun `aligned tables still enforce physical page bounds`() {
        val wide = PdfTable(listOf(490f), listOf(PdfTableRow(listOf(PdfTableCell(listOf(paragraph("MARK")))))))
            .withAlignment(PdfTableAlignment.RIGHT)
        val failure = assertThrows(PdfRenderException::class.java) { x(PdfDocument(page, listOf(wide), PdfFlow())) }
        assertEquals(PdfFailureCode.CONTENT_OVERFLOW, failure.code)
    }

}
