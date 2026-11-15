package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PdfLayoutOverflowDiagnosticsTest {
    private val style = PdfTextStyle("test", 10f)
    private val page = PdfPage(100f, 100f, PdfMargins(10f, 10f, 10f, 10f))
    private val measurer = TextMeasurer { text, _ -> TextMetrics(text.length * 10f, 8f, 2f) }

    private fun paragraph(text: String = "a", format: PdfParagraphFormat = PdfParagraphFormat(exactLineHeight = 10f),
        label: PdfListLabel? = null) = PdfParagraph(listOf(PdfTextRun(text, style)), 0f, 0f, format, label)

    private fun layout(vararg blocks: PdfBlock) = PageLayouter.layout(PdfDocument(page, blocks.toList()), measurer)

    private fun failure(reason: String, required: Float, available: Float, unit: String = "pt", action: () -> Any?) {
        val error = assertThrows(PdfRenderException::class.java) { action() }
        assertEquals(PdfFailureCode.CONTENT_OVERFLOW, error.code)
        assertEquals("$reason; required=$required $unit; available=$available $unit", error.message)
    }

    @Test
    fun `images page breaks and list markers identify the exact failing boundary`() {
        failure("Image exceeds area width", 81f, 80f) { layout(PdfImage(ByteArray(24), 81f, 1f)) }
        failure("Image exceeds remaining area height", 81f, 80f) { layout(PdfImage(ByteArray(24), 1f, 81f)) }
        failure("Page break requires another layout area", 2f, 1f, "areas") {
            layout(paragraph(), paragraph(format = PdfParagraphFormat(pageBreakBefore = true)))
        }
        val label = PdfListLabel(PdfListValue("list", 0, 1, "1"), style, PdfAlignment.RIGHT)
        failure("List marker exceeds left boundary", 10f, 0f) { layout(paragraph(label = label)) }
        failure("List marker exceeds right boundary", 85f, 80f) {
            layout(paragraph(format = PdfParagraphFormat(leftIndent = 75f), label = label.copy(alignment = PdfAlignment.LEFT)))
        }
    }

    @Test
    fun `line geometry distinguishes width height ascent descent and border spacing`() {
        failure("Line start leaves no content width", 0f, 0f) {
            PageLayouter.layout(PdfDocument(page, listOf(paragraph())), measurer, { 0f to 0f })
        }
        failure("Paragraph indents leave no content width", 90f, 80f) {
            layout(paragraph(format = PdfParagraphFormat(leftIndent = 50f, rightIndent = 40f, firstLineIndent = -50f)))
        }
        failure("Line box exceeds remaining area height", 81f, 80f) { layout(paragraph(format = PdfParagraphFormat(exactLineHeight = 81f))) }
        failure("Line ascent exceeds space above baseline", 8f, 4f) { layout(paragraph(format = PdfParagraphFormat(exactLineHeight = 5f))) }
        failure("Line descent exceeds space below baseline", 20f, 16f) {
            PageLayouter.layout(PdfDocument(page, listOf(paragraph(format = PdfParagraphFormat(exactLineHeight = 80f)))),
                TextMeasurer { text, _ -> TextMetrics(text.length * 10f, 8f, 20f) })
        }
        failure("Paragraph border spacing exceeds remaining height", 2f, 0f) {
            layout(PdfParagraph(listOf(PdfTextRun("a", style)), 0f, 0f, PdfParagraphFormat(exactLineHeight = 80f),
                borders = PdfParagraphBorders(bottom = PdfParagraphEdge(PdfLineStroke(), 1f))))
        }
    }

    @Test
    fun `tab failures distinguish backwards alignment start and payload overflow`() {

        fun tab(text: String, position: Float, alignment: PdfTabAlignment, prefix: String = ""): PdfParagraph =
            PdfParagraph(listOfNotNull(prefix.takeIf { it.isNotEmpty() }?.let { PdfTextRun(it, style) }) + PdfTab(style) + PdfTextRun(text, style),
                0f, 0f, PdfParagraphFormat(exactLineHeight = 10f, tabs = PdfTabSettings(listOf(PdfTabStop(position, alignment)))))

        failure("Tab alignment moves behind current text", 10f, -9f) { layout(tab("aa", 11f, PdfTabAlignment.RIGHT, "a")) }
        failure("Tab start exceeds line boundary", 81f, 80f) { layout(tab("a", 81f, PdfTabAlignment.LEFT)) }
        failure("Aligned tab content exceeds line boundary", 85f, 80f) { layout(tab("aa", 75f, PdfTabAlignment.CENTER)) }
    }

    @Test
    fun `words and whitespace preserve the actual available width after wrapping`() {
        failure("Word and leading spaces exceed line width", 90f, 80f) { layout(paragraph("aaaaaaaaa")) }
        failure("Word width exceeds line width", 70f, 60f) {
            layout(paragraph("aaaaaa aaaaaaa", PdfParagraphFormat(leftIndent = 20f, firstLineIndent = -20f, exactLineHeight = 10f)))
        }
        failure("Whitespace exceeds line width", 90f, 80f) { layout(paragraph(" ".repeat(9))) }
        assertDoesNotThrow { layout(paragraph("a".repeat(8), PdfParagraphFormat(exactLineHeight = 80f))) }
    }

    @Test
    fun `automatic line geometry reports finite limits and nonfinite calculations`() {
        val p = paragraph(format = PdfParagraphFormat(automaticLineSpacing = PdfAutomaticLineSpacing()))
        failure("Automatic line height is below minimum", 1f, .5f) {
            PageLayouter.automaticBox(p, TextMeasurer { _, _ -> TextMetrics(1f, .5f, 0f) })
        }
        failure("Automatic line height exceeds layout limit", 15000f, 14400f) {
            PageLayouter.automaticBox(p, TextMeasurer { _, _ -> TextMetrics(1f, 15000f, 0f) })
        }
        failure("Automatic line height is not finite", Float.POSITIVE_INFINITY, 14400f) {
            PageLayouter.automaticBox(p, TextMeasurer { _, _ -> TextMetrics(1f, Float.MAX_VALUE, Float.MAX_VALUE) })
        }
        failure("Automatic line height exceeds layout limit", 20000f, 14400f) {
            PageLayouter.automaticBox(PdfParagraph(listOf(PdfImageRun(PdfImage(ByteArray(24), 1f, 2000f))),
                format = PdfParagraphFormat(automaticLineSpacing = PdfAutomaticLineSpacing(10f))), measurer)
        }
    }
}
