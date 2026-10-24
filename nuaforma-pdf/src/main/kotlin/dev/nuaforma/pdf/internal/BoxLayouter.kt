package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.*
import kotlin.math.*

internal object BoxLayouter {
    fun layout(box: PdfTextBox, page: PdfPage, paragraphTop: Float, measurer: TextMeasurer): PositionedBox {
        val x = box.left
        val y = box.top + if (box.anchor == PdfBoxAnchor.PARAGRAPH) paragraphTop else 0f
        val angle = Math.toRadians(box.rotation.toDouble())
        val dx = (abs(cos(angle)) * box.width + abs(sin(angle)) * box.height) / 2
        val dy = (abs(sin(angle)) * box.width + abs(cos(angle)) * box.height) / 2
        val halfStroke = (box.border?.width ?: 0f) / 2
        if (x + box.width / 2 - dx < halfStroke - .001 || y + box.height / 2 - dy < halfStroke - .001 ||
            x + box.width / 2 + dx > page.width - halfStroke + .001 || y + box.height / 2 + dy > page.height - halfStroke + .001) overflow()
        val width = box.width - box.padding.left - box.padding.right
        var used = 0f
        var after = 0f
        for (p in box.paragraphs) {
            PageLayouter.checkCancellation()
            if (p.format.pageBreakBefore) throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Page breaks inside frames are unsupported")
            if (p.runs.any { it is PdfPageNumber }) throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Frame page fields are unsupported")
            val measured = FlowLayouter.paragraph(p, width, measurer)
            used += (if (p.format.collapseSpacing) maxOf(0f, p.spaceBefore - after) else p.spaceBefore) + measured.lines.size * measured.lineHeight + p.spaceAfter
            after = p.spaceAfter
        }
        val available = box.height - box.padding.top - box.padding.bottom
        if (used > available + .001f) overflow()
        val extra = when (box.verticalAlignment) { PdfVerticalAlignment.TOP -> 0f; PdfVerticalAlignment.CENTER -> (available - used) / 2; PdfVerticalAlignment.BOTTOM -> available - used }
        val local = PageLayouter.layout(PdfDocument(PdfPage(box.width, box.height,
            box.padding.copy(top = box.padding.top + extra, bottom = 0f)), box.paragraphs), measurer)
        val elements = listOf(PositionedRectangle(0f, 0f, box.width, box.height, box.fill, box.border)) + local.elements
        return PositionedBox(box, x, y, elements)
    }
    private fun overflow(): Nothing = throw PdfRenderException(PdfFailureCode.CONTENT_OVERFLOW, "Text frame exceeds its content area or physical page")
}
