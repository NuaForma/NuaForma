package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.*
import kotlin.math.*

internal object BoxLayouter {

    fun layout(box: PdfTextBox, page: PdfPage, paragraphTop: Float, measurer: TextMeasurer): PositionedBox {
        if (box.paragraphs.any { p -> p.runs.filterIsInstance<PdfTextRun>().any { it.navigation != null } })
            throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Navigation in text frames is not qualified")
        val x = box.left
        val y = box.top + if (box.anchor == PdfBoxAnchor.PARAGRAPH) paragraphTop else 0f
        val halfStroke = (box.border?.width ?: 0f) / 2
        if (box.autoFit == null) checkPageBounds(box, page, x, y, halfStroke)
        val padding = if (box.autoFit == null) box.padding else box.padding.let {
            PdfMargins(it.top + halfStroke, it.right + halfStroke, it.bottom + halfStroke, it.left + halfStroke)
        }
        val width = if (box.autoFit == PdfTextBoxAutoFit.WIDTH_AND_HEIGHT) naturalWidth(box, measurer)
            else box.width - padding.left - padding.right
        if (box.autoFit != null && (width < .001f || width + padding.left + padding.right > page.width + .001f)) overflow()
        var used = 0f
        var after = 0f
        for ((index, p) in box.paragraphs.withIndex()) {
            PageLayouter.checkCancellation()
            if (p.runs.any { it is PdfPageBreak }) throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Inline page breaks inside frames are not qualified")
            if (p.format.pageBreakBefore) throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Page breaks inside frames are unsupported")
            if (p.runs.any { it is PdfPageField }) throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Frame page fields are unsupported")
            val measured = FlowLayouter.paragraph(p, width, measurer, previous = box.paragraphs.getOrNull(index - 1), next = box.paragraphs.getOrNull(index + 1))
            used += ParagraphSpacing.before(p, after, index == 0) + measured.height() + p.spaceAfter
            after = p.spaceAfter
        }
        val resolved = if (box.autoFit == null) box else {
            val fitWidth = maxOf(1f, width + padding.left + padding.right)
            val fitHeight = maxOf(1f, used + padding.top + padding.bottom + .001f)
            if (fitHeight > page.height || fitWidth > page.width) overflow()
            box.resolvedSize(fitWidth, fitHeight)
        }
        if (box.autoFit != null) checkPageBounds(resolved, page, x, y, halfStroke)
        val available = resolved.height - padding.top - padding.bottom
        if (used > available + .001f) overflow()
        val extra = when (resolved.verticalAlignment) {
            PdfVerticalAlignment.TOP -> 0f
            PdfVerticalAlignment.CENTER -> (available - used) / 2
            PdfVerticalAlignment.BOTTOM -> available - used
        }
        val local = PageLayouter.layout(PdfDocument(PdfPage(resolved.width, resolved.height,
            padding.copy(top = padding.top + extra, bottom = 0f)), resolved.paragraphs), measurer)
        val elements = listOf(PositionedRectangle(0f, 0f, resolved.width, resolved.height, resolved.fill, resolved.border)) + local.elements
        return PositionedBox(resolved, x, y, elements)
    }

    private fun checkPageBounds(box: PdfTextBox, page: PdfPage, x: Float, y: Float, halfStroke: Float) {
        val angle = Math.toRadians(box.rotation.toDouble())
        val dx = (abs(cos(angle)) * box.width + abs(sin(angle)) * box.height) / 2
        val dy = (abs(sin(angle)) * box.width + abs(cos(angle)) * box.height) / 2
        if (x + box.width / 2 - dx < halfStroke - .001 || y + box.height / 2 - dy < halfStroke - .001 ||
            x + box.width / 2 + dx > page.width - halfStroke + .001 || y + box.height / 2 + dy > page.height - halfStroke + .001) overflow()
    }

    private fun naturalWidth(box: PdfTextBox, measurer: TextMeasurer): Float {
        val widths = box.paragraphs.map { paragraph ->
            val format = paragraph.format
            if (paragraph.listLabel != null || paragraph.runs.any { it !is PdfTextRun } ||
                format.textDirection != null ||
                format.leftIndent != 0f || format.rightIndent != 0f || format.firstLineIndent != 0f ||
                paragraph.hasVisualDecorations())
                throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Unwrapped autofit requires ordinary unindented frame text")
            paragraph.runs.sumOf { run ->
                PageLayouter.checkCancellation()
                val text = run as PdfTextRun
                measurer.measure(text.text, text.style).width.toDouble()
            }.toFloat()
        }
        return maxOf(.001f, widths.max() + .001f)
    }

    private fun PdfParagraph.hasVisualDecorations() = borders != null || background != null || lines.isNotEmpty()

    private fun overflow(): Nothing = throw PdfRenderException(PdfFailureCode.CONTENT_OVERFLOW, "Text frame exceeds its content area or physical page")

}
