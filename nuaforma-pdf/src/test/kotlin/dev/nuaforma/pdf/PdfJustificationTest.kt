package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PdfJustificationTest {
    private val style = PdfTextStyle("regular", 10f, underline = true)
    private val measurer = TextMeasurer { text, _ -> TextMetrics(text.length * 5f, 8f, 2f) }

    @Test
    fun `only wrapped lines stretch interior spaces while final lines and source remain unchanged`() {
        val paragraph = PdfParagraph(listOf(PdfTextRun("aa bb cc dd ee", style)), format = PdfParagraphFormat(
            alignment = PdfAlignment.JUSTIFY, exactLineHeight = 12f))
        val page = PageLayouter.layout(PdfDocument(PdfPage(50f, 100f, PdfMargins(0f, 0f, 0f, 0f)), listOf(paragraph)), measurer)
        val lines = page.elements.filterIsInstance<PositionedText>().groupBy { it.baseline }.values.toList()
        assertEquals(2, lines.size)
        assertEquals(listOf("aa", " ", "bb", " ", "cc"), lines.first().map { it.text })
        assertEquals(listOf(0f, 10f, 20f, 30f, 40f), lines.first().map { it.x })
        assertEquals(listOf(10f, 10f), lines.first().filter { it.text == " " }.map { it.trailingDecorationWidth })
        assertEquals(listOf(0f, 10f, 15f), lines.last().map { it.x })
        assertEquals("aa bb cc dd ee", (paragraph.runs.single() as PdfTextRun).text)
    }

    @Test
    fun `single words and unwrapped paragraphs do not stretch and unsupported combinations remain explicit`() {
        val format = PdfParagraphFormat(alignment = PdfAlignment.JUSTIFY, exactLineHeight = 12f)
        val page = PdfPage(50f, 100f, PdfMargins(0f, 0f, 0f, 0f))
        val single = PdfParagraph(listOf(PdfTextRun("aa bb", style)), format = format)
        val result = PageLayouter.layout(PdfDocument(page, listOf(single)), measurer)
        assertEquals(listOf(0f, 10f, 15f), result.elements.filterIsInstance<PositionedText>().map { it.x })
        val tabbed = PdfParagraph(listOf(PdfTextRun("aa", style), PdfTab(style), PdfTextRun("bb", style)), format = format)
        assertThrows(PdfRenderException::class.java) { PageLayouter.layout(PdfDocument(page, listOf(tabbed)), measurer) }
        val bidi = single.copy(format = format.copy(textDirection = PdfTextDirection.RIGHT_TO_LEFT))
        assertThrows(PdfRenderException::class.java) { PageLayouter.layout(PdfDocument(page, listOf(bidi)), measurer) }
    }

}
