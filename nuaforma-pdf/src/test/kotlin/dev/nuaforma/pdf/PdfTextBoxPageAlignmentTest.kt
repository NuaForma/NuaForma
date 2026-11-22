package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.BoxLayouter
import dev.nuaforma.pdf.internal.TextMeasurer
import dev.nuaforma.pdf.internal.TextMetrics
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PdfTextBoxPageAlignmentTest {
    private val page = PdfPage(300f, 200f, PdfMargins(20f, 20f, 20f, 20f))
    private val text = PdfTextRun("Example", PdfTextStyle("test", 10f))
    private val measure = TextMeasurer { content, _ -> TextMetrics(content.length * 5f, 8f, 2f) }

    private fun box(left: Float = 30f, anchor: PdfBoxAnchor = PdfBoxAnchor.PAGE) =
        PdfTextBox(left, 40f, 120f, 80f,
            listOf(PdfParagraph(listOf(text), spaceAfter = 0f, format = PdfParagraphFormat(exactLineHeight = 15f))),
            anchor, padding = PdfMargins(0f, 0f, 0f, 0f), border = PdfBorder(1f, PdfColor(0, 0, 0)))

    @Test
    fun `physical positions resolve after autofit in each axis`() {
        val fitted = box().withAutoFit(PdfTextBoxAutoFit.WIDTH_AND_HEIGHT)
        for ((horizontal,vertical) in listOf(PdfAlignment.LEFT to PdfVerticalAlignment.BOTTOM,
            PdfAlignment.CENTER to PdfVerticalAlignment.TOP, PdfAlignment.RIGHT to PdfVerticalAlignment.CENTER)) {
            val result = BoxLayouter.layout(fitted.withPageAlignment(PdfTextBoxPageAlignment(horizontal, vertical)), page, 0f, measure)
            val expectedX = when (horizontal) {
                PdfAlignment.LEFT -> 0f
                PdfAlignment.CENTER -> (page.width - result.box.width) / 2
                else -> page.width - result.box.width
            }
            val expectedY = when (vertical) {
                PdfVerticalAlignment.TOP -> 0f
                PdfVerticalAlignment.CENTER -> (page.height - result.box.height) / 2
                else -> page.height - result.box.height
            }
            assertEquals(expectedX, result.x, .002f)
            assertEquals(expectedY, result.top, .002f)
            assertEquals(36f, result.box.width, .002f)
            assertEquals(16f, result.box.height, .002f)
        }
    }

    @Test
    fun `an explicit page-edge alignment is required to clip the outward stroke`() {
        val error = assertThrows(PdfRenderException::class.java) { BoxLayouter.layout(box(0f), page, 0f, measure) }
        assertEquals(PdfFailureCode.CONTENT_OVERFLOW, error.code)
        val left = PdfTextBoxPageAlignment(PdfAlignment.LEFT, null)
        assertEquals(0f, BoxLayouter.layout(box(0f).withPageAlignment(left), page, 0f, measure).x)
        assertThrows(IllegalArgumentException::class.java) {
            box(anchor = PdfBoxAnchor.PARAGRAPH).withPageAlignment(left)
        }
        assertThrows(IllegalArgumentException::class.java) { PdfTextBoxPageAlignment(PdfAlignment.JUSTIFY, null) }
    }
}
