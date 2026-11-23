package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class InlineTextFrameLayoutTest {
    private val style = PdfTextStyle("fixture", 10f)
    private val page = PdfPage(200f, 240f, PdfMargins(20f, 20f, 20f, 20f))
    private val metrics = TextMeasurer { text, _ -> TextMetrics(text.length * 5f, 8f, 2f) }
    private val noPadding = PdfMargins(0f, 0f, 0f, 0f)
    private val frame = PdfTextBox(0f, 0f, 60f, 24f, listOf(PdfParagraph(listOf(PdfTextRun("Frame", style)),
        format = PdfParagraphFormat(exactLineHeight = 12f))), padding = noPadding)

    private fun paragraph(format: PdfParagraphFormat = PdfParagraphFormat()) =
        PdfParagraph(listOf(PdfTextRun(" ", style)), format = format)

    @Test
    fun `dedicated inline frames preserve alignment clearance and minimum height`() {
        val positions = mapOf(PdfAlignment.LEFT to 38f, PdfAlignment.CENTER to 70.5f, PdfAlignment.RIGHT to 103f)
        for ((alignment, left) in positions) {
            val format = PdfParagraphFormat(alignment = alignment, leftIndent = 10f, rightIndent = 12f, firstLineIndent = 5f)
            val padding = PdfMargins(4f, 5f, 6f, 3f)
            val minimum = paragraph(format).withMinimumLineHeight(40f)
            assertEquals(InlineTextFrameLayout.Line(left, 10f, 40f),
                InlineTextFrameLayout.measure(frame.withInlineParagraph(minimum, padding), minimum, page, metrics))
            val automatic = paragraph(format.copy(automaticLineSpacing = PdfAutomaticLineSpacing(1.5f)))
            assertEquals(InlineTextFrameLayout.Line(left, 4f, 39f),
                InlineTextFrameLayout.measure(frame.withInlineParagraph(automatic, padding), automatic, page, metrics))
        }
        val host = paragraph()
        assertEquals(InlineTextFrameLayout.Line(20f, 0f, 24f),
            InlineTextFrameLayout.measure(frame.withInlineParagraph(host), host, page, metrics))
    }

    @Test
    fun `inline frames reject mismatched line policies and report the physical width limit`() {
        for (format in listOf(PdfParagraphFormat(exactLineHeight = 20f),
            PdfParagraphFormat(textDirection = PdfTextDirection.RIGHT_TO_LEFT), PdfParagraphFormat(keepWithNext = true),
            PdfParagraphFormat(alignment = PdfAlignment.JUSTIFY),
            PdfParagraphFormat(automaticLineSpacing = PdfAutomaticLineSpacing(.95f)))) {
            val host = paragraph(format)
            val error = assertThrows(PdfRenderException::class.java) {
                InlineTextFrameLayout.measure(frame.withInlineParagraph(host), host, page, metrics)
            }
            assertEquals(PdfFailureCode.UNSUPPORTED_TEXT, error.code)
        }
        val host = paragraph(PdfParagraphFormat(leftIndent = 70f, rightIndent = 45f))
        val err = assertThrows(PdfRenderException::class.java) {
            InlineTextFrameLayout.measure(frame.withInlineParagraph(host), host, page, metrics)
        }
        assertEquals(PdfFailureCode.CONTENT_OVERFLOW, err.code)
        assertEquals("Inline text frame exceeds the line width; required=60.0 pt; available=45.0 pt", err.message)
    }

    @Test
    fun `inline frame model refuses visible content and floating frame combinations`() {
        val host = paragraph()
        val text = frame.paragraphs.single()
        for (box in listOf(
            PdfTextBox(0f, 0f, 60f, 24f, listOf(text), anchor = PdfBoxAnchor.PAGE),
            PdfTextBox(1f, 0f, 60f, 24f, listOf(text)),
            PdfTextBox(0f, 1f, 60f, 24f, listOf(text)),
            PdfTextBox(0f, 0f, 60f, 24f, listOf(text), rotation = 25f),
            PdfTextBox(0f, 0f, 60f, 24f, listOf(text), wrap = PdfBoxWrap.TOP_BOTTOM),
            frame.withAutoFit(PdfTextBoxAutoFit.HEIGHT), frame.withVisibleTextOverflow(true),
        )) assertEquals("Inline text frames require fixed unrotated geometry", assertThrows(IllegalArgumentException::class.java) {
            box.withInlineParagraph(host)
        }.message)
        for (content in listOf(text, PdfParagraph(listOf(PdfPageNumber(style))), host.withBackground(PdfColor(1, 2, 3)),
            host.withEndDestinations(listOf(PdfDestination("after-frame"))))) {
            assertEquals("Inline text frames require an otherwise empty paragraph", assertThrows(IllegalArgumentException::class.java) {
                frame.withInlineParagraph(content)
            }.message)
        }
    }

    @Test
    fun `body flow moves inline frames at a forced break or when the remaining space is too short`() {
        val before = PdfParagraph(listOf(PdfTextRun("Before", style)), format = PdfParagraphFormat(exactLineHeight = 180f))
        for (forced in listOf(false, true)) {
            val host = paragraph(PdfParagraphFormat(pageBreakBefore = forced))
            val pages = FlowLayouter.layout(PdfDocument(page, listOf(before, frame.withInlineParagraph(host)), PdfFlow()), metrics)
            assertEquals(2, pages.size)
            assertEquals("Before", pages.first().elements.filterIsInstance<PositionedText>().single().text)
            val positioned = pages.last().elements.filterIsInstance<PositionedBox>().single()
            assertEquals(20f, positioned.x)
            assertEquals(20f, positioned.top)
        }
    }

    @Test
    fun `body flow rejects an inline frame larger than a fresh page and incompatible wrapping`() {
        val host = paragraph()
        val tall = PdfTextBox(0f, 0f, 60f, 220f, frame.paragraphs).withInlineParagraph(host)
        val tooTall = assertThrows(PdfRenderException::class.java) {
            FlowLayouter.layout(PdfDocument(page, listOf(tall), PdfFlow()), metrics)
        }
        assertEquals(PdfFailureCode.CONTENT_OVERFLOW, tooTall.code)
        val wrapped = PdfTextBox(20f, 0f, 160f, 24f, frame.paragraphs, padding = noPadding, wrap = PdfBoxWrap.TOP_BOTTOM)
        val overlapping = assertThrows(PdfRenderException::class.java) {
            FlowLayouter.layout(PdfDocument(page, listOf(wrapped, frame.withInlineParagraph(host)), PdfFlow()), metrics)
        }
        assertEquals(PdfFailureCode.UNSUPPORTED_TEXT, overlapping.code)
        assertEquals("Inline frames combined with other wrapping objects are not qualified", overlapping.message)
    }
}
