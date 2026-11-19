package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.PageLayouter
import dev.nuaforma.pdf.internal.PositionedText
import dev.nuaforma.pdf.internal.TextMeasurer
import dev.nuaforma.pdf.internal.TextMetrics
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PdfTabCollisionTest {
    private val style = PdfTextStyle("regular", 10f)
    private val page = PdfPage(300f, 200f, PdfMargins(top = 30f, right = 30f, bottom = 30f, left = 56f))
    private val measurer = TextMeasurer { text, _ ->
        TextMetrics(if (text == "VALUE") 36.12f else text.length * 6f, 8f, 2f)
    }

    private fun layout(before: String, after: String, stops: List<PdfTabStop>, indent: Float = 0f): List<PositionedText> {
        val runs = listOfNotNull(before.takeIf { it.isNotEmpty() }?.let { PdfTextRun(it, style) }, PdfTab(style), PdfTextRun(after, style))
        val format = PdfParagraphFormat(exactLineHeight = 14f, firstLineIndent = indent, tabs = PdfTabSettings(stops))
        return PageLayouter.layout(PdfDocument(page, listOf(PdfParagraph(runs, 0f, 0f, format))), measurer)
            .elements.filterIsInstance<PositionedText>()
    }

    @Test
    fun `right center and decimal collisions preserve the preceding text endpoint for every leader`() {
        for (kind in listOf(PdfTabAlignment.RIGHT, PdfTabAlignment.CENTER, PdfTabAlignment.DECIMAL)) {
            for (leader in PdfTabLeader.entries) {
                val positions = layout("PREFIX", "1234567890", listOf(PdfTabStop(40f, kind, leader)))
                assertEquals(92f, positions.single { it.text == "1234567890" }.x, .001f, "$kind/$leader")
                assertTrue(positions.filter { it.decoration }.all { it.text.isBlank() })
                assertEquals(1, positions.count { it.text == "PREFIX" })
            }
        }
    }

    @Test
    fun `subpoint collision at a hanging first line is clamped without changing font measurements`() {
        val positions = layout("", "VALUE", listOf(PdfTabStop(0f, PdfTabAlignment.RIGHT)), -36f)
        assertEquals(20f, positions.single { it.text == "VALUE" }.x, .001f)
        assertEquals(36.12f, measurer.measure("VALUE", style).width, .001f)
    }

    @Test
    fun `passed custom stops select a later custom stop before automatic left stops`() {
        for (kind in PdfTabAlignment.entries) {
            val stops = listOf(PdfTabStop(36f, kind), PdfTabStop(144f, kind))
            val offset = when (kind) {
                PdfTabAlignment.LEFT -> 0f
                PdfTabAlignment.CENTER -> 18.06f
                else -> 36.12f
            }
            assertEquals(200f - offset, layout("LONGPREFIX", "VALUE", stops).single { it.text == "VALUE" }.x, .001f)
            assertEquals(128f, layout("LONGPREFIX", "VALUE", stops.take(1)).single { it.text == "VALUE" }.x, .001f)
        }
    }

    @Test
    fun `zero collision keeps ordinary field alignment instead of adding an artificial space`() {
        val positions = layout("ABCD", "ABCD", listOf(PdfTabStop(48f, PdfTabAlignment.RIGHT)))
        assertEquals(listOf(56f, 80f), positions.filter { it.text == "ABCD" }.map { it.x })
    }

}
