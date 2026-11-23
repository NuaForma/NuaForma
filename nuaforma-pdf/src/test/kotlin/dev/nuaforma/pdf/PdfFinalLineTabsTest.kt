package dev.nuaforma.pdf

import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import dev.nuaforma.pdf.internal.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PdfFinalLineTabsTest {
    private val style = PdfTextStyle("regular", 10f)
    private val page = PdfPage(260f, 180f, PdfMargins(20f, 20f, 20f, 20f))

    private fun paragraph(runs: List<PdfInline>, alignment: PdfAlignment, settings: PdfTabSettings) =
        PdfParagraph(runs, 0f, 0f, PdfParagraphFormat(alignment, exactLineHeight = 14f, tabs = settings, widowControl = false))

    @Test
    fun `qualified final lines preserve exact left-aligned pixels for every stop and leader kind`() {
        val fonts = FoundationLetter.fonts()
        val engine = PdfEngine()
        for (kind in PdfTabAlignment.entries) for (leader in PdfTabLeader.entries) {
            val settings = PdfTabSettings(listOf(PdfTabStop(120f, kind, leader)), decimalSeparator = ".")
            val runs = listOf(PdfTextRun("Label", style), PdfTab(style), PdfTextRun("123.45", style))
            val left = engine.render(PdfDocument(page, listOf(paragraph(runs, PdfAlignment.LEFT, settings))), fonts)
            val justified = engine.render(PdfDocument(page, listOf(paragraph(runs, PdfAlignment.JUSTIFY, settings))), fonts)
            Loader.loadPDF(left).use { before -> Loader.loadPDF(justified).use { after ->
                assertImagesMatch(PDFRenderer(before).renderImageWithDPI(0, 96f), PDFRenderer(after).renderImageWithDPI(0, 96f)) {
                    pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                }
            } }
        }
    }

    @Test
    fun `preceding ordinary wrapped lines remain justified before the final tabbed line`() {
        val measure = TextMeasurer { text, _ -> TextMetrics(text.length * 5f, 8f, 2f) }
        val runs = listOf(PdfTextRun("aa ".repeat(15) + "tail", style), PdfTab(style), PdfTextRun("END", style))
        val settings = PdfTabSettings(listOf(PdfTabStop(120f, PdfTabAlignment.RIGHT)))
        val block = paragraph(runs, PdfAlignment.JUSTIFY, settings)
        val smaller = PdfPage(197f, 180f, page.margins)
        val positions = FlowLayouter.layout(PdfDocument(smaller, listOf(block), PdfFlow()), measure)
            .flatMap { it.elements }.filterIsInstance<PositionedText>()
        assertEquals(2, positions.map { it.baseline }.distinct().size)
        val firstBaseline = positions.minOf { it.baseline }
        assertEquals(177f, positions.filter { it.baseline == firstBaseline }.maxOf { it.x + it.text.length * 5f }, .001f)
        assertEquals(140f, positions.single { it.text == "END" }.x + 15f, .001f)
    }

    @Test
    fun `wrapping after left tabs justifies the last field without moving preceding fields`() {
        val measure = TextMeasurer { text, _ -> TextMetrics(text.length * 5f, 8f, 2f) }
        val settings = PdfTabSettings(listOf(PdfTabStop(50f), PdfTabStop(100f, leader = PdfTabLeader.DOT)))
        val runs = listOf(PdfTextRun("A B", style), PdfTab(style), PdfTextRun("C D", style), PdfTab(style),
            PdfTextRun("E ".repeat(24), style))
        val reference = paragraph(runs, PdfAlignment.LEFT, settings)
        val justified = paragraph(runs, PdfAlignment.JUSTIFY, settings)
        fun firstLine(block: PdfParagraph): List<PositionedText> {
            val text = FlowLayouter.layout(PdfDocument(page, listOf(block), PdfFlow()), measure)
                .flatMap { it.elements }.filterIsInstance<PositionedText>()
            val baseline = text.minOf { it.baseline }
            return text.filter { it.baseline == baseline && it.text.isNotBlank() }
        }
        val before = firstLine(reference)
        val after = firstLine(justified)
        assertEquals(before.take(6).map { it.text to it.x }, after.take(6).map { it.text to it.x })
        assertEquals(listOf(20f, 30f, 70f, 80f), after.take(4).map { it.x })
        assertEquals(120f, after.first { it.text == "E" }.x)
        assertEquals(240f, after.last().x + 5f, .001f)
    }

    @Test
    fun `unqualified aligned tabs reject on a non-final line without affecting a subsequent render`() {
        val engine = PdfEngine()
        val fonts = FoundationLetter.fonts()
        val settings = PdfTabSettings(listOf(PdfTabStop(60f, PdfTabAlignment.RIGHT)))
        val bad = paragraph(listOf(PdfTextRun("L", style), PdfTab(style), PdfTextRun("END", style),
            PdfLineBreak(style), PdfTextRun("Next", style)), PdfAlignment.JUSTIFY, settings)
        assertEquals(PdfFailureCode.UNSUPPORTED_TEXT, assertThrows(PdfRenderException::class.java) {
            engine.render(PdfDocument(page, listOf(bad), PdfFlow()), fonts)
        }.code)
        val good = paragraph(listOf(PdfTextRun("L", style), PdfTab(style), PdfTextRun("END", style)), PdfAlignment.JUSTIFY, settings)
        Loader.loadPDF(engine.render(PdfDocument(page, listOf(good)), fonts)).use { assertEquals(1, it.numberOfPages) }
        for (alignment in listOf(PdfAlignment.CENTER, PdfAlignment.RIGHT)) assertThrows(PdfRenderException::class.java) {
            engine.render(PdfDocument(page, listOf(paragraph(good.runs, alignment, settings))), fonts)
        }
    }

}
