package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.PageLayouter
import dev.nuaforma.pdf.internal.PositionedText
import dev.nuaforma.pdf.internal.ReferenceLayouter
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

    private fun layout(before: String, after: String, stops: List<PdfTabStop>, indent: Float = 0f, rightIndent: Float = 0f): List<PositionedText> {
        val runs = listOfNotNull(before.takeIf { it.isNotEmpty() }?.let { PdfTextRun(it, style) }, PdfTab(style), PdfTextRun(after, style))
        val format = PdfParagraphFormat(exactLineHeight = 14f, firstLineIndent = indent, rightIndent = rightIndent, tabs = PdfTabSettings(stops))
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
    fun `aligned tabs beyond the text area use the paragraph right edge`() {
        for (kind in listOf(PdfTabAlignment.RIGHT, PdfTabAlignment.CENTER, PdfTabAlignment.DECIMAL)) {
            val positions = layout("PREFIX", "VALUE", listOf(PdfTabStop(230f, kind)), rightIndent = 13f)
            assertEquals(220.88f, positions.single { it.text == "VALUE" }.x, .001f, kind.name)
        }
    }

    @Test
    fun `a right stop at the text area boundary can use the paragraph indent`() {
        val positions = layout("PREFIX", "ABCDE VALUE", listOf(PdfTabStop(214f, PdfTabAlignment.RIGHT)), rightIndent = 13f)
        val prefix = positions.single { it.text == "PREFIX" }
        val value = positions.single { it.text == "VALUE" }
        assertEquals(233.88f, value.x, .001f)
        assertEquals(prefix.baseline, value.baseline, .001f)
    }

    @Test
    fun `right edge fallback does not overlap preceding text when the field cannot fit`() {
        val error = assertThrows(PdfRenderException::class.java) {
            layout("A".repeat(29), "VALUE", listOf(PdfTabStop(230f, PdfTabAlignment.RIGHT)), rightIndent = 13f)
        }
        assertEquals(PdfFailureCode.CONTENT_OVERFLOW, error.code)
        assertTrue(error.message.orEmpty().contains("Aligned tab content exceeds line boundary"))
    }

    @Test
    fun `zero collision keeps ordinary field alignment instead of adding an artificial space`() {
        val positions = layout("ABCD", "ABCD", listOf(PdfTabStop(48f, PdfTabAlignment.RIGHT)))
        assertEquals(listOf(56f, 80f), positions.filter { it.text == "ABCD" }.map { it.x })
    }


    @Test
    fun centeredAndDecimalFieldsFitTheRightEdgeWithoutMovingEarlierText() {
        for ((alignment, payload, expectedX) in listOf(
            Triple(PdfTabAlignment.CENTER, "VALUE", 233.88f),
            Triple(PdfTabAlignment.DECIMAL, "1234.56", 228f),
        )) {
            val positions = layout("PREFIX", payload, listOf(PdfTabStop(210f, alignment)))
            val prefix = positions.single { it.text == "PREFIX" }
            val value = positions.single { it.text == payload }
            assertEquals(56f, prefix.x, .001f)
            assertEquals(expectedX, value.x, .001f)
            assertEquals(prefix.baseline, value.baseline, .001f)
        }
    }


    @Test
    fun rightAlignedFieldsAtTheTextEdgeRespectThePhysicalPage() {
        fun placed(prefix: String, stop: Float): List<PositionedText> {
            val doc = PdfDocument(page, listOf(rightTabParagraph(prefix, stop)), PdfFlow())
            val result = ReferenceLayouter.layout(doc, measurer, PdfRenderControl())
            return result.pages.single().elements.filterIsInstance<PositionedText>()
        }

        val prefix = "A".repeat(33)
        val inside = placed(prefix, 213f)
        assertEquals(232.88f, inside.single { it.text == "VALUE" }.x, .001f)
        assertEquals(14f, inside.single { it.text == "VALUE" }.baseline - inside.single { it.text == prefix }.baseline, .001f)

        val outside = placed(prefix, 214.05f)
        assertEquals(254f, outside.single { it.text == "VALUE" }.x, .001f)
        assertEquals(outside.single { it.text == prefix }.baseline, outside.single { it.text == "VALUE" }.baseline, .001f)

        val failure = assertThrows(PdfRenderException::class.java) { placed("A".repeat(35), 214.05f) }
        assertEquals(PdfFailureCode.CONTENT_OVERFLOW, failure.code)
    }

    private fun rightTabParagraph(prefix: String, stop: Float): PdfParagraph {
        val tabs = PdfTabSettings(listOf(PdfTabStop(stop, PdfTabAlignment.RIGHT)))
        return PdfParagraph(listOf(PdfTextRun(prefix, style), PdfTab(style), PdfTextRun("VALUE", style)), 0f, 0f,
            PdfParagraphFormat(exactLineHeight = 14f, tabs = tabs))
    }


    @Test
    fun anOrdinaryHeaderAndFooterCanUseThePhysicalRightMargin() {
        val prefix = "A".repeat(33)
        val band = PdfPageBand(0f, 25f, listOf(rightTabParagraph(prefix, 214.05f)))
        val body = PdfParagraph(listOf(PdfTextRun("Body", style)), format = PdfParagraphFormat(exactLineHeight = 14f))
        val result = ReferenceLayouter.layout(PdfDocument(page, listOf(body), PdfFlow(header = band, footer = band)),
            measurer, PdfRenderControl())
        val placed = result.pages.single().elements.filterIsInstance<PositionedText>()
        val values = placed.filter { it.text == "VALUE" }
        assertEquals(2, values.size)
        assertTrue(values.all { kotlin.math.abs(254f - it.x) < .001f })
        assertEquals(placed.filter { it.text == prefix }.map { it.baseline }, values.map { it.baseline })
    }


    @Test
    fun anUnpaintedFinalTabKeepsItsLineAndVisibleText() {
        val runs = listOf(PdfTextRun("PREFIX", style), PdfTab(style))
        fun placed(leader: PdfTabLeader): dev.nuaforma.pdf.internal.PageLayout {
            val tabs = PdfTabSettings(listOf(PdfTabStop(260f, PdfTabAlignment.LEFT, leader)))
            val paragraph = PdfParagraph(runs, 0f, 0f, PdfParagraphFormat(exactLineHeight = 14f, tabs = tabs))
            return PageLayouter.layout(PdfDocument(page, listOf(paragraph)), measurer)
        }
        val positions = placed(PdfTabLeader.NONE).elements.filterIsInstance<PositionedText>()
        assertEquals(56f, positions.single { it.text == "PREFIX" }.x, .001f)
        assertEquals(positions.single { it.text == "PREFIX" }.baseline, positions.last().baseline, .001f)
        assertTrue(positions.last().text.isBlank())

        val fillingPrefix = "A".repeat(35)
        val automatic = PdfParagraph(listOf(PdfTextRun(fillingPrefix, style), PdfTab(style)), 0f, 0f,
            PdfParagraphFormat(exactLineHeight = 14f))
        val autoPlaced = PageLayouter.layout(PdfDocument(page, listOf(automatic)), measurer)
            .elements.filterIsInstance<PositionedText>()
        assertEquals(fillingPrefix, autoPlaced.first().text)
        assertEquals(autoPlaced.first().baseline, autoPlaced.last().baseline, .001f)
        val painted = assertThrows(PdfRenderException::class.java) { placed(PdfTabLeader.DOT) }
        assertEquals(PdfFailureCode.CONTENT_OVERFLOW, painted.code)
    }

    @Test
    fun `multiple trailing plain tabs keep the lines used by Word`() {
        val format = PdfParagraphFormat(exactLineHeight = 14f, tabs = PdfTabSettings(defaultInterval = 120f))
        val next = PdfParagraph(listOf(PdfTextRun("NEXT", style)), 0f, 0f, format)
        for (number in listOf(2, 6)) {
            val tabs = List(number) { PdfTab(style) }
            val first = PdfParagraph(listOf(PdfTextRun("PREFIX", style)) + tabs, 0f, 0f, format)
            val printed = PageLayouter.layout(PdfDocument(page, listOf(first, next)), measurer)
                .elements.filterIsInstance<PositionedText>().filter { it.text.isNotBlank() }
            assertEquals(listOf("PREFIX", "NEXT"), printed.map { it.text })
            assertEquals(14f * number, printed[1].baseline - printed[0].baseline, .001f)
        }
        for (ending in listOf(PdfTextRun("END", style), PdfTab(style.copy(underline = true)))) {
            val first = PdfParagraph(listOf(PdfTextRun("PREFIX", style), PdfTab(style), PdfTab(style), ending), 0f, 0f, format)
            val failure = assertThrows(PdfRenderException::class.java) {
                PageLayouter.layout(PdfDocument(page, listOf(first)), measurer)
            }
            assertEquals(PdfFailureCode.CONTENT_OVERFLOW, failure.code)
        }
    }

}
