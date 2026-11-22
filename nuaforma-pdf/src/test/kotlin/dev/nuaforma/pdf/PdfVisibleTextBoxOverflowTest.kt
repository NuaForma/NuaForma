package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.BoxLayouter
import dev.nuaforma.pdf.internal.PositionedRectangle
import dev.nuaforma.pdf.internal.PositionedText
import dev.nuaforma.pdf.internal.TextMeasurer
import dev.nuaforma.pdf.internal.TextMetrics
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PdfVisibleTextBoxOverflowTest {
    private val page = PdfPage(300f, 200f, PdfMargins(20f, 20f, 20f, 20f))
    private val style = PdfTextStyle("test", 10f)
    private val measure = TextMeasurer { content, _ -> TextMetrics(content.length * 5f, 8f, 2f) }

    private fun frame(top: Float = 40f, alignment: PdfVerticalAlignment = PdfVerticalAlignment.TOP) =
        PdfTextBox(48f, top, 100f, 24f, (1..3).map { line ->
            PdfParagraph(listOf(PdfTextRun("Line $line", style)), spaceAfter = 0f,
                format = PdfParagraphFormat(exactLineHeight = 16f))
        }, PdfBoxAnchor.PAGE, padding = PdfMargins(2f, 2f, 2f, 2f),
            border = PdfBorder(0.5f, PdfColor(0, 0, 0)), verticalAlignment = alignment)

    @Test
    fun `excess text starts at the top and leaves the frame rectangle unchanged`() {
        for (alignment in PdfVerticalAlignment.entries) {
            val placed = BoxLayouter.layout(frame(alignment = alignment).withVisibleTextOverflow(true), page, 0f, measure)
            assertEquals(24f, placed.elements.filterIsInstance<PositionedRectangle>().single().height)
            assertEquals(50f, placed.contentHeight)
            val baselines = placed.elements.filterIsInstance<PositionedText>().map { it.baseline }.distinct()
            assertEquals(3, baselines.size)
            baselines.zipWithNext { before, after -> assertEquals(16f, after - before, .001f) }
            assertTrue(baselines.first() < 24f && baselines.last() > 24f)
        }
    }

    @Test
    fun `default overflow and the physical page boundary retain measured failures`() {
        val normal = assertThrows(PdfRenderException::class.java) { BoxLayouter.layout(frame(), page, 0f, measure) }
        assertEquals(PdfFailureCode.CONTENT_OVERFLOW, normal.code)
        assertEquals("Text exceeds frame content height; required=48.0 pt; available=20.0 pt", normal.message)
        val physical = assertThrows(PdfRenderException::class.java) {
            BoxLayouter.layout(frame(170f).withVisibleTextOverflow(true), page, 0f, measure)
        }
        assertEquals(PdfFailureCode.CONTENT_OVERFLOW, physical.code)
        assertEquals("Text below the frame exceeds the physical page; required=50.0 pt; available=30.0 pt", physical.message)
        assertThrows(IllegalArgumentException::class.java) {
            frame().withVisibleTextOverflow(true).withAutoFit(PdfTextBoxAutoFit.HEIGHT)
        }
    }
}
