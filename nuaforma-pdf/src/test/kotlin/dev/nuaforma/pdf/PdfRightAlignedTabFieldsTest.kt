package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.PageLayouter
import dev.nuaforma.pdf.internal.PositionedText
import dev.nuaforma.pdf.internal.TextMeasurer
import dev.nuaforma.pdf.internal.TextMetrics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PdfRightAlignedTabFieldsTest {
    private val style = PdfTextStyle("regular", 10f)
    private val page = PdfPage(280f, 200f, PdfMargins(30f, 30f, 30f, 30f))
    private val measure = TextMeasurer { value, _ -> TextMetrics(value.length * 5f, 8f, 2f) }

    private fun layout(lastStop: Float, lastField: String = "Right", first: String = "Left"): Map<String, PositionedText> {
        val runs = listOf(PdfTextRun(first, style), PdfTab(style), PdfTextRun("Center", style), PdfTab(style)) +
            lastField.takeIf { it.isNotEmpty() }?.let { listOf(PdfTextRun(it, style)) }.orEmpty()
        val settings = PdfTabSettings(listOf(PdfTabStop(110f, PdfTabAlignment.CENTER), PdfTabStop(lastStop, PdfTabAlignment.RIGHT)))
        val format = PdfParagraphFormat(PdfAlignment.RIGHT, exactLineHeight = 14f, tabs = settings)
        return PageLayouter.layout(PdfDocument(page, listOf(PdfParagraph(runs, 0f, 0f, format))), measure)
            .elements.filterIsInstance<PositionedText>().associateBy { it.text }
    }

    @Test
    fun `a right tab at or beyond the edge keeps the other fields fixed`() {
        for (stop in listOf(220f, 234.4f)) {
            val positions = layout(stop)
            assertEquals(30f, positions.getValue("Left").x, .001f)
            assertEquals(125f, positions.getValue("Center").x, .001f)
            assertEquals(225f, positions.getValue("Right").x, .001f)
            assertEquals(positions.getValue("Left").baseline, positions.getValue("Right").baseline, .001f)
        }
    }

    @Test
    fun `an inset right tab shifts both preceding fields including when its payload is empty`() {
        for (right in listOf("", "Right")) {
            val positions = layout(202f, right)
            assertEquals(48f, positions.getValue("Left").x, .001f)
            assertEquals(143f, positions.getValue("Center").x, .001f)
        }
    }

    @Test
    fun `a prefix past the center stop does not silently choose another stop`() {
        val error = assertThrows(PdfRenderException::class.java) { layout(220f, first = "A".repeat(23)) }
        assertEquals("Right-aligned tab fields require both explicit stops", error.message)
    }
}
