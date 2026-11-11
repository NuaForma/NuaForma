package dev.nuaforma.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.cos.COSNumber
import org.apache.pdfbox.pdfparser.PDFStreamParser
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PdfTrailingUnderlineTest {
    private val style = PdfTextStyle("regular", 11f, underline = true)

    private fun paragraph(text: String, alignment: PdfAlignment, enabled: Boolean?) = PdfParagraph(
        listOf(PdfTextRun(text, style)), 0f, 0f, PdfParagraphFormat(alignment, exactLineHeight = 16f))
        .withUnderlineTrailingSpaces(enabled)

    private fun render(blocks: List<PdfBlock>) = PdfEngine().render(PdfDocument(
        PdfPage(300f, 500f, PdfMargins(20f, 20f, 20f, 20f)), blocks, PdfFlow()), FoundationLetter.fonts())

    private fun bars(bytes: ByteArray): List<List<Float>> = Loader.loadPDF(bytes).use { pdf ->
        val tokens = PDFStreamParser(pdf.getPage(0)).parse()
        tokens.mapIndexedNotNull { index, token ->
            if (token is Operator && token.name == "re") (index - 4 until index).map { (tokens[it] as COSNumber).floatValue() }
            else null
        }
    }

    private fun visiblePositions(bytes: ByteArray): List<Triple<String, Float, Float>> = Loader.loadPDF(bytes).use { pdf ->
        val positions = mutableListOf<Triple<String, Float, Float>>()
        object : PDFTextStripper() {

            override fun processTextPosition(position: TextPosition) {
                if (position.unicode.isNotBlank()) positions += Triple(position.unicode, position.xDirAdj, position.yDirAdj)
                super.processTextPosition(position)
            }

        }.getText(pdf)
        positions
    }

    @Test
    fun `end spaces decorate without changing aligned glyph positions and clip at the margin`() {
        for (alignment in PdfAlignment.entries) {
            val off = render(listOf(paragraph("TRAIL    ", alignment, false)))
            val on = render(listOf(paragraph("TRAIL    ", alignment, true)))
            assertEquals(visiblePositions(off), visiblePositions(on), alignment.name)
            val before = bars(off).sumOf { it[2].toDouble() }
            val after = bars(on).sumOf { it[2].toDouble() }
            assertEquals(if (alignment == PdfAlignment.RIGHT) 0.0 else 12.2246, after - before, .03, alignment.name)
            assertTrue(bars(on).all { it[0] + it[2] <= 280.001f })
        }
    }

    @Test
    fun `spaces only remain a line and long end spaces are bounded`() {
        for (alignment in PdfAlignment.entries) {
            val off = render(listOf(paragraph("        ", alignment, false)))
            val on = render(listOf(paragraph("        ", alignment, true)))
            assertTrue(bars(off).isEmpty())
            val width = bars(on).sumOf { it[2].toDouble() }
            assertEquals(if (alignment == PdfAlignment.RIGHT) 0.0 else 24.4492, width, .04)
        }
        val clipped = bars(render(listOf(paragraph("X" + " ".repeat(200), PdfAlignment.LEFT, true))))
        assertEquals(280.0, clipped.maxOf { (it[0] + it[2]).toDouble() }, .02)
    }

    @Test
    fun `immutable underline policy survives copy helpers and does not alter the default`() {
        val original = paragraph("COPY ", PdfAlignment.LEFT, null)
        val changed = original.withUnderlineTrailingSpaces(true).withMinimumLineStyle(style).copy(spaceAfter = 2f)
            .withSpacing(PdfParagraphSpacing()).withPageOffset(null)
        assertNull(original.underlineTrailingSpaces)
        assertEquals(true, changed.underlineTrailingSpaces)
        assertNull(changed.withUnderlineTrailingSpaces(null).underlineTrailingSpaces)
        val baseline = render(listOf(original))
        val reset = render(listOf(original.withUnderlineTrailingSpaces(null)))
        assertEquals(bars(baseline), bars(reset))
        assertEquals(visiblePositions(baseline), visiblePositions(reset))
    }

    @Test
    fun `tables frames and page bands retain the explicit trailing decoration`() {
        fun p() = paragraph("X    ", PdfAlignment.LEFT, true)

        val table = PdfTable(listOf(100f), listOf(PdfTableRow(listOf(PdfTableCell(listOf(p()))))))
        val frame = PdfTextBox(140f, 120f, 100f, 50f, listOf(p()), PdfBoxAnchor.PAGE)
        val document = PdfDocument(PdfPage(300f, 500f, PdfMargins(60f, 20f, 60f, 20f)), listOf(p(), table, frame),
            PdfFlow(header = PdfPageBand(5f, 40f, listOf(p())), footer = PdfPageBand(5f, 40f, listOf(p()))))
        val widths = bars(PdfEngine().render(document, FoundationLetter.fonts())).map { it[2] }
        // Each of the five contexts contains a painted word and a separate four-space underline.
        assertEquals(5, widths.count { kotlin.math.abs(it - 12.2246f) < .04f })
    }

    @Test
    fun `unqualified bidi trailing decoration fails instead of guessing visual edges`() {
        val original = paragraph("X ", PdfAlignment.LEFT, true)
        val bidi = original.copy(format = PdfParagraphFormat(exactLineHeight = 16f, textDirection = PdfTextDirection.LEFT_TO_RIGHT))
        val failure = assertThrows(PdfRenderException::class.java) { render(listOf(bidi)) }
        assertEquals(PdfFailureCode.UNSUPPORTED_TEXT, failure.code)
        assertDoesNotThrow { render(listOf(bidi.withUnderlineTrailingSpaces(false))) }
    }

    @Test
    fun `Word trailing decoration includes strikeout alone or combined with underlining`() {
        for (underline in listOf(false, true)) {
            val decorated = style.copy(underline = underline).withStrikethrough(true)

            fun p(text: String, enabled: Boolean) = PdfParagraph(listOf(PdfTextRun(text, decorated)), 0f, 0f,
                PdfParagraphFormat(exactLineHeight = 16f))
                .withUnderlineTrailingSpaces(enabled)

            val off = render(listOf(p("STRIKE    ", false)))
            val on = render(listOf(p("STRIKE    ", true)))
            assertEquals(visiblePositions(off), visiblePositions(on))
            val extra = bars(on).sumOf { it[2].toDouble() } - bars(off).sumOf { it[2].toDouble() }
            val count = if (underline) 2 else 1
            assertEquals(count * 12.2246, extra, .04)
            assertTrue(bars(render(listOf(p("        ", false)))).isEmpty())
            assertEquals(count * 24.4492, bars(render(listOf(p("        ", true)))).sumOf { it[2].toDouble() }, .06)
        }
    }

}
