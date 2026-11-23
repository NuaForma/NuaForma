package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.*

/** Physical placement of a frame occupying a single otherwise empty body line. */
internal object InlineTextFrameLayout {
    data class Line(val left: Float, val top: Float, val height: Float)

    fun measure(frame: PdfTextBox, paragraph: PdfParagraph, page: PdfPage, measurer: TextMeasurer): Line {
        val format = paragraph.format
        val multiple = format.automaticLineSpacing?.multiple ?: 1f
        if (format.exactLineHeight != null || format.textDirection != null || format.keepWithNext ||
            format.alignment == PdfAlignment.JUSTIFY || multiple < 1f)
            throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Inline text frames require automatic or minimum line spacing")
        ParagraphBounds.check(paragraph, page)
        val nominal = paragraph.withMinimumLineHeight(null).copy(
            format = format.copy(automaticLineSpacing = PdfAutomaticLineSpacing(1f)))
        val natural = PageLayouter.automaticBox(nominal, measurer)?.first
            ?: throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Inline text frame line metrics unavailable")
        val pad = frame.inlinePadding
        val inlineHeight = frame.height + pad.top + pad.bottom
        val advance = maxOf(inlineHeight, natural, paragraph.minimumLineHeight ?: 0f)
        val left = page.margins.left + format.leftIndent + format.firstLineIndent
        val available = page.width - page.margins.right - format.rightIndent - left
        val remaining = available - frame.width - pad.left - pad.right
        if (remaining < -.001f) throw PdfRenderException(PdfFailureCode.CONTENT_OVERFLOW,
            "Inline text frame exceeds the line width; required=${frame.width + pad.left + pad.right} pt; available=$available pt")
        val offset = when (format.alignment) {
            PdfAlignment.LEFT -> 0f
            PdfAlignment.CENTER -> remaining / 2f
            PdfAlignment.RIGHT -> remaining
            PdfAlignment.JUSTIFY -> error("Justification was rejected above")
        }
        return Line(left + offset + pad.left, advance - frame.height - pad.bottom, advance + (multiple - 1f) * natural)
    }
}
