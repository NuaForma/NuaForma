package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PdfLongWordBreaksTest {
    private val style = PdfTextStyle("regular", 10f)
    private val page = PdfPage(55f, 400f, PdfMargins(10f, 10f, 10f, 10f))
    private val measurer = TextMeasurer { text, _ -> TextMetrics(text.codePointCount(0, text.length) * 10f, 8f, 2f) }

    private fun paragraph(vararg runs: PdfInline) = PdfParagraph(runs.toList(), 0f, 0f, PdfParagraphFormat(exactLineHeight = 10f))

    private fun layout(p: PdfParagraph) = PageLayouter.layout(PdfDocument(page, listOf(p)), measurer)

    private fun lines(p: PdfParagraph): List<String> = layout(p).elements.filterIsInstance<PositionedText>()
        .groupBy { it.baseline }.values.map { row -> row.joinToString("") { it.text } }

    @Test
    fun `word breaking is opt in and ordinary words retain their layout`() {
        val long = paragraph(PdfTextRun("ABCDEFG", style))
        assertFalse(long.breakLongWords)
        assertEquals(PdfFailureCode.CONTENT_OVERFLOW, assertThrows(PdfRenderException::class.java) { layout(long) }.code)
        assertEquals(listOf("ABC", "DEF", "G"), lines(long.withLongWordBreaking(true)))
        val ordinary = paragraph(PdfTextRun("a b", style))
        assertEquals(layout(ordinary), layout(ordinary.withLongWordBreaking(true)))
    }

    @Test
    fun `styled words preserve all source offsets and ordinary word boundaries`() {
        val p = paragraph(PdfTextRun("AB", style), PdfTextRun("CDE", style.copy(size = 12f)), PdfTextRun("FG", style))
            .withLongWordBreaking(true).copy(semantics = PdfBlockSemantics("paragraph", PdfSemanticRole.PARAGRAPH))
        assertEquals(listOf("ABC", "DEF", "G"), lines(p))
        val text = layout(p).elements.filterIsInstance<PositionedText>()
        assertEquals(listOf("AB", "C", "DE", "F", "G"), text.map { it.text })
        assertEquals(listOf(0, 2, 3, 5, 6), text.map { it.logicalOffset })
        assertEquals(listOf(10f, 12f, 12f, 10f, 10f), text.map { it.style.size })
        assertEquals(listOf("a", "ABC", "DEF", "G"), lines(paragraph(PdfTextRun("a ABCDEFG", style)).withLongWordBreaking(true)))
        assertEquals(listOf("ABC", "DEF", "G", "hi"), lines(paragraph(PdfTextRun("ABCDEFG hi", style)).withLongWordBreaking(true)))
        assertEquals(listOf("ABC", "DEF", "G", "hi"), lines(paragraph(PdfTextRun("ABCDEFG", style), PdfLineBreak(style), PdfTextRun("hi", style)).withLongWordBreaking(true)))
    }

    @Test
    fun `immutable copy methods retain the breaking policy`() {
        val original = PdfParagraph(listOf(PdfTextRun("a", style)))
        val enabled = original.withLongWordBreaking(true)
        assertFalse(original.breakLongWords)
        for (copy in listOf(enabled.copy(), enabled.withSpacing(PdfParagraphSpacing()), enabled.withPageOffset(null),
            enabled.withMinimumLineStyle(style), enabled.withMinimumLineHeight(12f), enabled.withUnderlineTrailingSpaces(true),
            enabled.withParagraphMarkStyle(style))) assertTrue(copy.breakLongWords)
        assertFalse(enabled.withLongWordBreaking(false).breakLongWords)
    }

    @Test
    fun `Unicode clusters remain intact even across source runs`() {

        fun chunks(runs: List<Pair<String, PdfTextStyle>>): List<String> {
            val breaker = LongWordBreaks(runs, measurer)
            val result = mutableListOf<String>()
            while (breaker.hasMore) result += breaker.next(25f).joinToString("") { runs[it.run].first.substring(it.from, it.until) }
            return result
        }

        val flag = "\uD83C\uDDF3\uD83C\uDDF1"
        assertEquals(listOf("A", flag, "B"), chunks(listOf("A${flag}B" to style)))
        assertEquals(listOf("A", flag, "B"), chunks(listOf("A\uD83C\uDDF3" to style, "\uD83C\uDDF1B" to style)))
    }

    @Test
    fun `an indivisible cluster that cannot fit retains measured overflow`() {
        val breaker = LongWordBreaks(listOf("AB" to style), measurer)
        val error = assertThrows(PdfRenderException::class.java) { breaker.next(5f) }
        assertEquals(PdfFailureCode.CONTENT_OVERFLOW, error.code)
        assertEquals("Word cluster width exceeds line width; required=10.0 pt; available=5.0 pt", error.message)
    }

    @Test
    fun `shaped oversized words reject explicitly while fitting words remain supported`() {
        val shaped = style.withFontFeatures(PdfFontFeatures(kerning = PdfKerning.PAIRS))
        val error = assertThrows(PdfRenderException::class.java) {
            lines(paragraph(PdfTextRun("ABCDEFG", shaped)).withLongWordBreaking(true))
        }
        assertEquals(PdfFailureCode.UNSUPPORTED_TEXT, error.code)
        assertTrue(error.message!!.contains("font shaping"))
        assertEquals(listOf("AB"), lines(paragraph(PdfTextRun("AB", shaped)).withLongWordBreaking(true)))
    }

    @Test
    fun `maximum source length stays linear and cancellation remains cooperative`() {
        var calls = 0
        var measuredCharacters = 0
        val counted = TextMeasurer { text, font ->
            calls++
            measuredCharacters += text.length
            measurer.measure(text, font)
        }
        val source = "ABCD".repeat(25000)
        val breaker = LongWordBreaks(listOf(source to style), counted)
        val restored = StringBuilder()
        while (breaker.hasMore) for (part in breaker.next(35f)) restored.append(source, part.from, part.until)
        assertEquals(source, restored.toString())
        assertTrue(calls <= 150000)
        assertEquals(200000, measuredCharacters)
        try {
            Thread.currentThread().interrupt()
            assertEquals(PdfFailureCode.CANCELLED, assertThrows(PdfRenderException::class.java) {
                LongWordBreaks(listOf("ABC" to style), measurer)
            }.code)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `real font output matches explicit line controls in all three faces`() {
        val narrow = PdfPage(30f, 200f, PdfMargins(10f, 10f, 10f, 10f))
        for (face in listOf("regular", "bold", "italic")) {
            val font = style.copy(fontId = face)
            val automatic = paragraph(PdfTextRun("WWWW", font)).copy(format = PdfParagraphFormat()).withLongWordBreaking(true)
            val forced = paragraph(PdfTextRun("W", font), PdfLineBreak(font), PdfTextRun("W", font), PdfLineBreak(font),
                PdfTextRun("W", font), PdfLineBreak(font), PdfTextRun("W", font)).copy(format = PdfParagraphFormat())
            val a = PdfEngine().render(PdfDocument(narrow, listOf(automatic)), FoundationLetter.fonts())
            val b = PdfEngine().render(PdfDocument(narrow, listOf(forced)), FoundationLetter.fonts())
            Loader.loadPDF(a).use { first -> Loader.loadPDF(b).use { second ->
                assertEquals(1, first.numberOfPages)
                assertEquals(PDFTextStripper().getText(second), PDFTextStripper().getText(first))
                val x = PDFRenderer(first).renderImageWithDPI(0, 144f)
                val y = PDFRenderer(second).renderImageWithDPI(0, 144f)
                assertArrayEquals(x.getRGB(0, 0, x.width, x.height, null, 0, x.width), y.getRGB(0, 0, y.width, y.height, null, 0, y.width))
            } }
        }
    }
}
