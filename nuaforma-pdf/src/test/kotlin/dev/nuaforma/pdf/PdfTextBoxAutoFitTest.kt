package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.BoxLayouter
import dev.nuaforma.pdf.internal.PositionedText
import dev.nuaforma.pdf.internal.TextMeasurer
import dev.nuaforma.pdf.internal.TextMetrics
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PdfTextBoxAutoFitTest {
    private val page = PdfPage(300f, 200f, PdfMargins(20f, 20f, 20f, 20f))
    private val style = PdfTextStyle("test", 10f)
    private val border = PdfBorder(.5f, PdfColor(0, 0, 0))
    private val padding = PdfMargins(2f, 3f, 4f, 1f)
    private val measurer = TextMeasurer { text, _ -> TextMetrics(text.length * 5f, 8f, 2f) }

    private fun paragraph(text: String) = PdfParagraph(listOf(PdfTextRun(text, style)), spaceAfter = 0f,
        format = PdfParagraphFormat(exactLineHeight = 15f))

    private fun frame(height: Float, width: Float = 100f) = PdfTextBox(30f, 40f, width, height,
        listOf(paragraph("two words"), paragraph("short")), PdfBoxAnchor.PAGE, padding = padding, border = border)

    @Test
    fun `height fitting can shrink or grow cached frames without changing anchor or width`() {
        for (height in listOf(14f, 80f)) {
            val original = frame(height)
            val placed = BoxLayouter.layout(original.withAutoFit(PdfTextBoxAutoFit.HEIGHT), page, 0f, measurer)
            assertEquals(height, original.height)
            assertNull(original.autoFit)
            assertEquals(100f, placed.box.width, .002f)
            assertEquals(36.5f, placed.box.height, .002f)
            assertEquals(30f, placed.x)
            assertEquals(40f, placed.top)
            assertEquals(14.25f, placed.elements.filterIsInstance<PositionedText>().first().baseline, .002f)
        }
    }

    @Test
    fun `unwrapped fitting resolves the longest text line and rejects a frame wider than its physical page`() {
        for (width in listOf(15f, 100f)) {
            val placed = BoxLayouter.layout(frame(30f, width).withAutoFit(PdfTextBoxAutoFit.WIDTH_AND_HEIGHT), page, 0f, measurer)
            assertEquals(49.5f, placed.box.width, .002f)
            assertEquals(36.5f, placed.box.height, .002f)
        }
        val invalid = PdfTextBox(30f, 40f, 40f, 40f,
            listOf(paragraph("A".repeat(80))), PdfBoxAnchor.PAGE, padding = padding)
            .withAutoFit(PdfTextBoxAutoFit.WIDTH_AND_HEIGHT)
        val error = assertThrows(PdfRenderException::class.java) { BoxLayouter.layout(invalid, page, 0f, measurer) }
        assertEquals(PdfFailureCode.CONTENT_OVERFLOW, error.code)
    }
}
