package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PdfLinesTest {
    private val style = PdfTextStyle("regular", 10f)
    private val measurer = TextMeasurer { text, _ -> TextMetrics(text.length * 5f, 8f, 2f) }
    private val edge = PdfParagraphEdge(PdfLineStroke(1f, PdfColor(0, 139, 144)), 4f)
    private fun paragraph(borders: PdfParagraphBorders?, text: String = "Text", after: Float = 8f) =
        PdfParagraph(listOf(PdfTextRun(text, style)), spaceAfter = after, format = PdfParagraphFormat(exactLineHeight = 16f), borders = borders)

    @Test fun `previously compiled Kotlin default constructor still has its original binary signature`() {
        val constructor = PdfParagraph::class.java.getConstructor(List::class.java, Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType, PdfParagraphFormat::class.java, PdfListLabel::class.java, List::class.java,
            PdfBlockSemantics::class.java, PdfContents::class.java, Int::class.javaPrimitiveType,
            Class.forName("kotlin.jvm.internal.DefaultConstructorMarker"))
        val value = constructor.newInstance(listOf(PdfTextRun("Old consumer", style)), 0f, 0f, null, null, null, null, null, 254, null)
        assertEquals(8f, value.spaceAfter); assertNull(value.borders); assertTrue(value.lines.isEmpty())
    }

    @Test fun `border spacing changes layout without changing text metrics or collapsed spacing`() {
        val p = paragraph(PdfParagraphBorders(edge, edge, edge, edge))
        val measured = FlowLayouter.paragraph(p, 180f, measurer)
        assertEquals(26f, measured.height())
        assertEquals(16f, measured.lineHeight)
        val text = measured.lines.single().filterIsInstance<PositionedText>().single()
        assertEquals(17.8f, text.baseline, .001f)
        val strokes = measured.lines.single().filterIsInstance<PositionedStroke>()
        assertEquals(4, strokes.size)
        assertEquals(-6.5f, strokes.minOf { it.x })
        val plain = FlowLayouter.paragraph(paragraph(null), 180f, measurer)
        assertEquals(16f, plain.height())
    }

    @Test fun `identical neighboring borders merge and between lines reserve both spaces`() {
        val p = paragraph(PdfParagraphBorders(edge, edge, edge, edge))
        val first = FlowLayouter.paragraph(p, 180f, measurer, next = p)
        val last = FlowLayouter.paragraph(p, 180f, measurer, previous = p)
        assertEquals(21f, first.height()); assertEquals(21f, last.height())
        assertEquals(3, first.lines.single().filterIsInstance<PositionedStroke>().size)
        val divided = paragraph(PdfParagraphBorders(edge, edge, edge, edge, edge))
        val middle = FlowLayouter.paragraph(divided, 180f, measurer, previous = divided, next = divided)
        assertEquals(25f, middle.height())
        val between = middle.lines.single().filterIsInstance<PositionedStroke>().single { it.top == it.endY }
        assertEquals(28.5f, between.top)
    }

    @Test fun `double borders reserve total stroke extent and indentation breaks grouping`() {
        val double = PdfParagraphEdge(PdfLineStroke(2f, style = PdfLineStyle.DOUBLE), 3f)
        val p = paragraph(PdfParagraphBorders(bottom = double))
        assertEquals(25f, FlowLayouter.paragraph(p, 180f, measurer).height())
        val different = PdfParagraph(p.runs, format = PdfParagraphFormat(leftIndent = 12f, exactLineHeight = 16f), borders = p.borders)
        assertFalse(ParagraphLines.frame(p, next = different).next)
    }

    @Test fun `line axes and defensive collections survive immutable paragraph copies`() {
        val lines = mutableListOf(PdfLineDrawing(20f, 40f, 100f, 40f, pageY = true))
        val p = PdfParagraph(listOf(PdfTextRun("Line", style)), format = PdfParagraphFormat(exactLineHeight = 16f), borders = null, lines = lines)
        lines.clear(); assertEquals(1, p.lines.size)
        val measured = FlowLayouter.paragraph(p, 180f, measurer)
        val placed = FlowLayouter.translate(measured.lines.single().filterIsInstance<PositionedStroke>().single(), 30f, 50f) as PositionedStroke
        assertEquals(50f, placed.x); assertEquals(40f, placed.top)
        val doc = PdfDocument(PdfPage(240f, 180f, PdfMargins(20f, 20f, 20f, 20f)), listOf(p))
        val copied = DocumentTransforms.map(doc, true) { value, _, _ -> listOf(value) }
        assertEquals(p.lines, (copied.blocks.single() as PdfParagraph).lines)
    }

    @Test fun `invalid unbounded and empty vector geometry rejects immediately`() {
        for (width in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, 5f))
            assertThrows(IllegalArgumentException::class.java) { PdfLineStroke(width) }
        assertThrows(IllegalArgumentException::class.java) { PdfLineDrawing(0f, 0f, 0f, 0f) }
        assertThrows(IllegalArgumentException::class.java) { PdfLineDrawing(0f, 0f, Float.NaN, 1f) }
        assertThrows(IllegalArgumentException::class.java) { PdfParagraphEdge(edge.stroke, 32f) }
        assertThrows(IllegalArgumentException::class.java) { PdfLineStroke(style = PdfLineStyle.DASHED, dashOn = 2f) }
        assertThrows(IllegalArgumentException::class.java) { PdfLineStroke(dashOn = 2f, dashOff = 1f) }
        assertThrows(IllegalArgumentException::class.java) { PdfLineStroke(style = PdfLineStyle.DOTTED, dashOn = 1f, dashOff = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { PdfParagraph(listOf(PdfTextRun("x", style)), borders = null, lines = List(33) { PdfLineDrawing(0f, 1f, 10f, 1f) }) }
    }

    @Test fun `bordered paragraphs continue across pages without invented closing or opening rules`() {
        val p = paragraph(PdfParagraphBorders(edge, edge, edge, edge), "Long bordered paragraph. ".repeat(30))
        val document = PdfDocument(PdfPage(240f, 120f, PdfMargins(20f, 20f, 20f, 20f)), listOf(p), PdfFlow())
        val pages = FlowLayouter.layout(document, measurer)
        assertTrue(pages.size >= 3)
        val horizontal = pages.map { page -> page.elements.filterIsInstance<PositionedStroke>().count { it.top == it.endY } }
        assertEquals(1, horizontal.first()); assertEquals(1, horizontal.last())
        assertTrue(horizontal.drop(1).dropLast(1).all { it == 0 })
        assertTrue(pages.all { page -> page.elements.filterIsInstance<PositionedStroke>().any { it.x == it.endX } })
    }

    @Test fun `aggregate vector budget and physical stroke extents are enforced`() {
        val marks = List(32) { PdfLineDrawing(0f, 1f, 10f, 1f) }
        val p = PdfParagraph(listOf(PdfTextRun("x", style)), spaceAfter = 0f,
            format = PdfParagraphFormat(exactLineHeight = 16f), borders = null, lines = marks)
        val large = PdfDocument(PdfPage(240f, 14_000f, PdfMargins(20f, 20f, 20f, 20f)), List(626) { p })
        assertEquals(PdfFailureCode.RESOURCE_LIMIT, assertThrows(PdfRenderException::class.java) { PdfEngine().render(large, FoundationLetter.fonts()) }.code)
        val clipped = PdfParagraph(p.runs, borders = null, lines = listOf(PdfLineDrawing(0f, 30f, 0f, 70f, pageX = true, pageY = true)))
        assertEquals(PdfFailureCode.CONTENT_OVERFLOW, assertThrows(PdfRenderException::class.java) {
            PdfEngine().render(PdfDocument(PdfPage(240f, 180f, PdfMargins(20f, 20f, 20f, 20f)), listOf(clipped)), FoundationLetter.fonts())
        }.code)
    }

    @Test fun `joined sides bridge extra spacing only when the following paragraph is on the same canvas`() {
        val borders = PdfParagraphBorders(edge, edge, edge, edge, edge)
        val format = PdfParagraphFormat(exactLineHeight = 16f, collapseSpacing = true)
        val first = PdfParagraph(listOf(PdfTextRun("First", style)), format = format, borders = borders)
        val second = PdfParagraph(listOf(PdfTextRun("Second", style)), spaceBefore = 20f, format = format, borders = borders)
        val page = PdfPage(240f, 240f, PdfMargins(20f, 20f, 20f, 20f))
        fun sides(elements: List<PositionedElement>) = elements.filterIsInstance<PositionedStroke>().filter { it.x == it.endX && it.x < 100f }.sortedBy { it.top }
        for (layout in listOf(FlowLayouter.layout(PdfDocument(page, listOf(first, second), PdfFlow()), measurer).single(),
            PageLayouter.layout(PdfDocument(page, listOf(first, second)), measurer))) {
            val edges = sides(layout.elements)
            assertEquals(2, edges.size); assertEquals(70f, edges.first().endY)
            assertEquals(edges.first().endY, edges.last().top)
        }
        val short = page.copy(height = 80f)
        val split = FlowLayouter.layout(PdfDocument(short, listOf(first, second), PdfFlow()), measurer)
        assertEquals(2, split.size); assertEquals(58f, sides(split.first().elements).single().endY)
        assertEquals(20f, sides(split.last().elements).single().top)
        val bandPage = PdfPage(300f, 400f, PdfMargins(120f, 20f, 20f, 20f))
        val band = PdfPageBand(10f, 100f, listOf(first, second))
        val body = PdfDocument(bandPage, listOf(paragraph(null)), PdfFlow(header = band))
        val raw = FlowLayouter.layout(body, measurer)
        val header = sides(FlowLayouter.decorate(raw, body.flow!!, 1, 1, measurer).single().elements)
        assertEquals(2, header.size); assertEquals(header.first().endY, header.last().top)
    }

    @Test fun `concurrent rendering keeps line geometry request local`() {
        val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
        val engine = PdfEngine()
        try {
            val futures = (1..12).map { n -> executor.submit<Int> {
                val p = paragraph(PdfParagraphBorders(bottom = edge), "Request $n")
                val bytes = engine.render(PdfDocument(PdfPage(240f, 180f, PdfMargins(20f, 20f, 20f, 20f)), listOf(p), PdfFlow()), FoundationLetter.fonts())
                org.apache.pdfbox.Loader.loadPDF(bytes).use { pdf ->
                    assertEquals("Request $n", org.apache.pdfbox.text.PDFTextStripper().getText(pdf).trim()); pdf.numberOfPages
                }
            } }
            futures.forEach { assertEquals(1, it.get(30, java.util.concurrent.TimeUnit.SECONDS)) }
        } finally { executor.shutdownNow() }
    }
}
