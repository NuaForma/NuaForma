package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.FlowLayouter
import dev.nuaforma.pdf.internal.PositionedText
import dev.nuaforma.pdf.internal.TextMeasurer
import dev.nuaforma.pdf.internal.TextMetrics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PdfAutomaticPageEndSpacingTest {
    private val style = PdfTextStyle("reference", 10f)
    private val page = PdfPage(120f, 100f, PdfMargins(20f, 20f, 20f, 20f))
    private val measurer = TextMeasurer { text, _ -> TextMetrics(text.length * 5f, 8f, 2f) }

    private fun paragraph(text: String, format: PdfParagraphFormat) =
        PdfParagraph(listOf(PdfTextRun(text.ifEmpty { " " }, style)), spaceAfter = 0f, format = format)

    private fun layout(remaining: Float, text: String, policy: PdfParagraphFormat): List<List<PositionedText>> {
        val paragraphs = listOf(
            paragraph("Fill", PdfParagraphFormat(exactLineHeight = 60f - remaining)),
            paragraph(text, policy),
            paragraph("Next", PdfParagraphFormat(exactLineHeight = 14f)),
        )
        return FlowLayouter.layout(PdfDocument(page, paragraphs, PdfFlow()), measurer)
            .map { it.elements.filterIsInstance<PositionedText>() }
    }

    @Test
    fun `automatic extra leading at the page end leaves visible and blank lines on the same page`() {
        val policy = PdfParagraphFormat(automaticLineSpacing = PdfAutomaticLineSpacing(1.5f))
        for (text in listOf("Fit", "")) {
            val exact = layout(11f, text, policy)
            assertEquals(2, exact.size)
            assertEquals(if (text.isEmpty()) listOf("Fill") else listOf("Fill", text), exact[0].map { it.text }.filter { it.isNotBlank() })
            if (text.isNotEmpty()) assertEquals(77f, exact[0].last().baseline, .001f)
            assertEquals(31.2f, exact[1].single().baseline, .001f)
            val short = layout(9f, text, policy)
            assertEquals(2, short.size)
            assertEquals(1, short[0].size)
            assertEquals(46.2f, short[1].last().baseline, .001f)
        }
    }

    @Test
    fun `fixed and kept lines still require their whole line area`() {
        val exact = PdfParagraphFormat(exactLineHeight = 15f)
        val kept = PdfParagraphFormat(keepLines = true, automaticLineSpacing = PdfAutomaticLineSpacing(1.5f))
        for (policy in listOf(exact, kept)) {
            val pages = layout(11f, "Fit", policy)
            assertEquals(1, pages[0].size)
            assertEquals(listOf("Fit", "Next"), pages[1].map { it.text })
        }
    }
}
