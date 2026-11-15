package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PdfLineBreakTest {
    private val style = PdfTextStyle("regular", 10f)
    private val page = PdfPage(200f, 200f, PdfMargins(10f, 10f, 10f, 10f))
    private val measure = TextMeasurer { text, font ->
        assertFalse(text.contains('\n'))
        TextMetrics(text.length * 10f, font.size * .8f, font.size * .2f)
    }

    private fun paragraph(vararg runs: PdfInline, format: PdfParagraphFormat = PdfParagraphFormat(exactLineHeight = 10f)) =
        PdfParagraph(runs.toList(), 0f, 0f, format)

    private fun layout(p: PdfParagraph) = PageLayouter.layout(PdfDocument(page, listOf(p)), measure)

    @Test
    fun `line breaks retain one paragraph and restart text at the continuation indent`() {
        val p = paragraph(PdfTextRun("ab", style), PdfLineBreak(style), PdfTextRun("cd", style),
            format = PdfParagraphFormat(leftIndent = 20f, firstLineIndent = 10f, exactLineHeight = 10f))
        val result = layout(p)
        val text = result.elements.filterIsInstance<PositionedText>()
        assertEquals(listOf("ab", "cd"), text.map { it.text })
        assertEquals(listOf(40f, 30f), text.map { it.x })
        assertEquals(listOf(18f, 28f), text.map { it.baseline })
        assertEquals(2, result.lineBoxes.size)
    }

    @Test
    fun `leading consecutive and trailing controls preserve every empty line`() {
        val result = layout(paragraph(PdfLineBreak(style), PdfTextRun("a", style), PdfLineBreak(style), PdfLineBreak(style)))
        assertEquals(4, result.lineBoxes.size)
        assertEquals(listOf(18f, 28f, 38f, 48f), result.elements.filterIsInstance<PositionedText>().map { it.baseline })
        assertEquals(listOf("", "a", "", ""), result.elements.filterIsInstance<PositionedText>().map { it.text })
        assertEquals(2, layout(paragraph(PdfLineBreak(style))).lineBoxes.size)
    }

    @Test
    fun `only empty lines use break or paragraph-mark metrics like Word`() {
        val p = paragraph(PdfTextRun("a", style), PdfLineBreak(style.copy(size = 20f)), PdfTextRun("b", style),
            format = PdfParagraphFormat(automaticLineSpacing = PdfAutomaticLineSpacing()))
        val result = layout(p)
        assertEquals(listOf(10f, 10f), result.lineBoxes.map { it.height })
        assertEquals(listOf("a", "b"), result.elements.filterIsInstance<PositionedText>().map { it.text })
        val empty = paragraph(PdfLineBreak(style.copy(size = 20f)), format = PdfParagraphFormat(automaticLineSpacing = PdfAutomaticLineSpacing()))
            .withParagraphMarkStyle(style.copy(size = 12f))
        assertEquals(listOf(20f, 12f), layout(empty).lineBoxes.map { it.height })
        val minimum = p.withMinimumLineStyle(style.copy(size = 20f))
        assertEquals(listOf(20f, 20f), layout(minimum).lineBoxes.map { it.height })
    }

    @Test
    fun `tab alignment and automatic table widths stop at a forced line end`() {
        val p = paragraph(PdfTab(style), PdfTextRun("ab", style), PdfLineBreak(style), PdfTextRun("cdef", style),
            format = PdfParagraphFormat(exactLineHeight = 10f, tabs = PdfTabSettings(listOf(PdfTabStop(50f, PdfTabAlignment.RIGHT)))))
        val texts = layout(p).elements.filterIsInstance<PositionedText>().filter { it.text.isNotBlank() }
        assertEquals(listOf(40f, 10f), texts.map { it.x })
        val widths = PageLayouter.intrinsicWidths(paragraph(PdfTextRun("ab", style), PdfLineBreak(style), PdfTextRun("cdef", style)), measure)
        assertEquals(40f to 40f, widths)
    }

    @Test
    fun `bidi source offsets include the line control and never encode a newline glyph`() {
        val result = layout(paragraph(PdfTextRun("אבג", style), PdfLineBreak(style), PdfTextRun("דהו", style),
            format = PdfParagraphFormat(exactLineHeight = 10f, textDirection = PdfTextDirection.RIGHT_TO_LEFT, alignment = PdfAlignment.RIGHT)))
        val text = result.elements.filterIsInstance<PositionedText>()
        assertEquals(listOf("גבא", "והד"), text.map { it.text })
        assertEquals(listOf(18f, 28f), text.map { it.baseline })
    }

    @Test
    fun `controls are bounded and real PDF output retains content and empty lines`() {
        val many = PdfParagraph(List(1000) { PdfLineBreak(style) })
        assertDoesNotThrow { PdfDocument(page, listOf(many, many)) }
        assertThrows(IllegalArgumentException::class.java) { PdfDocument(page, listOf(many, many, paragraph(PdfLineBreak(style)))) }
        for (p in listOf(paragraph(PdfTextRun("Alpha", style), PdfLineBreak(style), PdfTextRun("Beta", style)), paragraph(PdfLineBreak(style)))) {
            Loader.loadPDF(PdfEngine().render(PdfDocument(page, listOf(p), PdfFlow()), FoundationLetter.fonts())).use { pdf ->
                assertEquals(1, pdf.numberOfPages)
                val text = PDFTextStripper().getText(pdf)
                assertFalse(text.contains('\uFFFD'))
                if (p.runs.any { it is PdfTextRun }) assertTrue(text.contains("Alpha") && text.contains("Beta"))
                else assertTrue(text.isBlank())
            }
        }
    }

    @Test
    fun `list labels remain single and natural table width uses the widest explicit line`() {
        val label = PdfListLabel(PdfListValue("list", 0, 1, "1"), style, suffix = PdfListSuffix.SPACE)
        val p = paragraph(PdfTextRun("one", style), PdfLineBreak(style), PdfTextRun("two", style),
            format = PdfParagraphFormat(leftIndent = 20f, firstLineIndent = -20f, exactLineHeight = 10f)).copy(listLabel = label)
        assertEquals(listOf("1", "one", "two"), layout(p).elements.filterIsInstance<PositionedText>().map { it.text })

        fun table(p: PdfParagraph) = PdfTable(listOf(80f), listOf(PdfTableRow(listOf(PdfTableCell(listOf(p))))), widthPolicy = PdfTableWidthPolicy.FIT_CONTENTS)

        val broken = table(paragraph(PdfTextRun("ab", style), PdfLineBreak(style), PdfTextRun("cdef", style)))
        val plain = table(paragraph(PdfTextRun("cdef", style)))
        assertEquals(TableWidthResolver.resolve(plain, measure, 180f).columnWidths, TableWidthResolver.resolve(broken, measure, 180f).columnWidths)
    }

    @Test
    fun `paragraph mark style is immutable and survives every existing copy path`() {
        val original = PdfParagraph(listOf(PdfLineBreak(style)))
        val mark = style.copy(size = 12f)
        val changed = original.withParagraphMarkStyle(mark)
        assertNull(original.paragraphMarkStyle)
        for (copy in listOf(changed.copy(), changed.withSpacing(PdfParagraphSpacing()), changed.withPageOffset(null),
            changed.withMinimumLineStyle(style), changed.withMinimumLineHeight(20f), changed.withUnderlineTrailingSpaces(true)))
            assertSame(mark, copy.paragraphMarkStyle)
        assertNull(changed.withParagraphMarkStyle(null).paragraphMarkStyle)
    }
}
