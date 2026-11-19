package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PdfWhitespaceLineMetricsTest {

    @Test
    fun `space-only styles retain their advances without enlarging visible text line metrics`() {
        val plain = PdfTextStyle("regular", 10f)
        val large = plain.copy(size = 30f)
        val measurer = TextMeasurer { text, style -> TextMetrics(text.length * style.size, style.size * .8f, style.size * .2f) }
        val paragraph = PdfParagraph(listOf(PdfTextRun("A", plain), PdfTextRun("   ", large), PdfTextRun("B", plain)),
            0f, 0f, PdfParagraphFormat(automaticLineSpacing = PdfAutomaticLineSpacing()))
        assertEquals(10f to 8f, PageLayouter.automaticBox(paragraph, measurer))
        val layout = PageLayouter.layout(PdfDocument(PdfPage(200f, 200f, PdfMargins(20f, 20f, 20f, 20f)), listOf(paragraph)), measurer)
        val end = layout.elements.filterIsInstance<PositionedText>().single { it.text == "B" }
        assertEquals(120f, end.x)
        assertEquals(28f, end.baseline)
        assertTrue(end.requiresPhysicalBounds)
    }

}
