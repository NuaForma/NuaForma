package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.PageLayouter
import dev.nuaforma.pdf.internal.PositionedText
import dev.nuaforma.pdf.internal.TextMeasurer
import dev.nuaforma.pdf.internal.TextMetrics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PdfListHighlightTest {
    @Test
    fun `numbering highlight follows the suffix without marking the item text`() {
        val background = PdfColor(255, 255, 0)
        val plain = PdfTextStyle("regular", 10f)
        val style = plain.withBackground(background)
        val page = PdfPage(180f, 180f, PdfMargins(20f, 20f, 20f, 20f))
        val metrics = TextMeasurer { text, _ -> TextMetrics(text.length * 5f, 8f, 2f) }
        val value = PdfListValue("list", 0, 1, "1.")
        val cases = listOf(
            Triple(PdfListLabel(value, style, tabStop = 40f), 10f, 60f),
            Triple(PdfListLabel(value, style, tabStop = 40f, highlightSuffix = true), 25f, 60f),
            Triple(PdfListLabel(value, style, suffix = PdfListSuffix.SPACE, highlightSuffix = true), 15f, 50f),
            Triple(PdfListLabel(value, style, suffix = PdfListSuffix.NOTHING, highlightSuffix = true), 10f, 45f),
        )
        for ((label, paintWidth, bodyX) in cases) {
            val paragraph = PdfParagraph(listOf(PdfTextRun("body", plain)), 0f, 0f,
                PdfParagraphFormat(leftIndent = 30f, firstLineIndent = -15f), listLabel = label)
            val items = PageLayouter.layout(PdfDocument(page, listOf(paragraph)), metrics).elements.filterIsInstance<PositionedText>()
            val marker = items.first()
            assertEquals("1.", marker.text)
            assertEquals(35f, marker.x)
            assertEquals(paintWidth, requireNotNull(marker.background).width)
            assertEquals(bodyX, items[1].x)
            assertNull(items[1].background)
        }
    }
}
