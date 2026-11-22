package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.*
import kotlin.math.*

internal object BoxLayouter {

    fun layout(box: PdfTextBox, page: PdfPage, paragraphTop: Float, measurer: TextMeasurer): PositionedBox {
        if (box.paragraphs.any { paragraph ->
                paragraph.endDestinations.isNotEmpty() || paragraph.runs.any { run ->
                    when (run) {
                        is PdfTextRun -> run.navigation?.let { link ->
                            box.rotation != 0f || box.anchor != PdfBoxAnchor.PAGE ||
                                link.link !is PdfLink.External || link.destinations.isNotEmpty()
                        } ?: false
                        is PdfTab -> run.navigation != null
                        else -> false
                    }
                }
            }) throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Navigation in text frames is not qualified")
        val halfStroke = (box.border?.width ?: 0f) / 2
        val fixedPosition = if (box.autoFit == null) locate(box, page, paragraphTop, halfStroke) else null
        val padding = if (box.autoFit == null) box.padding else box.padding.let {
            PdfMargins(it.top + halfStroke, it.right + halfStroke, it.bottom + halfStroke, it.left + halfStroke)
        }
        val width = if (box.autoFit == PdfTextBoxAutoFit.WIDTH_AND_HEIGHT) naturalWidth(box, measurer)
            else box.width - padding.left - padding.right
        if (box.autoFit != null) {
            val minimum = padding.left + padding.right + .001f
            if (width < .001f) overflow("Text frame padding leaves no usable width", minimum, box.width)
            val required = width + padding.left + padding.right
            if (required > page.width + .001f) overflow("Auto-fitted text frame exceeds page width", required, page.width)
        }
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
            if (fitHeight > page.height) overflow("Auto-fitted text frame exceeds page height", fitHeight, page.height)
            if (fitWidth > page.width) overflow("Auto-fitted text frame exceeds page width", fitWidth, page.width)
            box.resolvedSize(fitWidth, fitHeight)
        }
        val (x, y) = fixedPosition ?: locate(resolved, page, paragraphTop, halfStroke)
        val available = resolved.height - padding.top - padding.bottom
        val excess = used > available + .001f
        if (excess && !resolved.visibleTextOverflow) overflow("Text exceeds frame content height", used, available)
        val layoutHeight = if (excess) padding.top + used else resolved.height
        if (excess && y + layoutHeight > page.height + .001f)
            overflow("Text below the frame exceeds the physical page", layoutHeight, page.height - y)
        val extra = if (excess) 0f else when (resolved.verticalAlignment) {
            PdfVerticalAlignment.TOP -> 0f
            PdfVerticalAlignment.CENTER -> (available - used) / 2
            PdfVerticalAlignment.BOTTOM -> available - used
        }
        val local = PageLayouter.layout(PdfDocument(PdfPage(resolved.width, layoutHeight,
            padding.copy(top = padding.top + extra, bottom = 0f)), resolved.paragraphs), measurer)
        val elements = listOf(PositionedRectangle(0f, 0f, resolved.width, resolved.height, resolved.fill, resolved.border)) + local.elements
        return PositionedBox(resolved, x, y, elements, contentHeight = layoutHeight)
    }

    private fun locate(box: PdfTextBox, page: PdfPage, paragraphTop: Float, halfStroke: Float): Pair<Float, Float> {
        val horizontal = box.pageAlignment?.horizontal
        val vertical = box.pageAlignment?.vertical
        val x = when (horizontal) {
            PdfAlignment.LEFT -> 0f
            PdfAlignment.CENTER -> (page.width - box.width) / 2
            PdfAlignment.RIGHT -> page.width - box.width
            else -> box.left
        }
        val y = when (vertical) {
            PdfVerticalAlignment.TOP -> 0f
            PdfVerticalAlignment.CENTER -> (page.height - box.height) / 2
            PdfVerticalAlignment.BOTTOM -> page.height - box.height
            null -> box.top + if (box.anchor == PdfBoxAnchor.PARAGRAPH) paragraphTop else 0f
        }
        val angle = Math.toRadians(box.rotation.toDouble())
        val dx = (abs(cos(angle)) * box.width + abs(sin(angle)) * box.height) / 2
        val dy = (abs(sin(angle)) * box.width + abs(cos(angle)) * box.height) / 2
        // The physical page clips the outward half of a stroke explicitly aligned to that edge.
        val minX = if (horizontal == PdfAlignment.LEFT) 0f else halfStroke
        val maxX = page.width - if (horizontal == PdfAlignment.RIGHT) 0f else halfStroke
        val minY = if (vertical == PdfVerticalAlignment.TOP) 0f else halfStroke
        val maxY = page.height - if (vertical == PdfVerticalAlignment.BOTTOM) 0f else halfStroke
        val midX = x + box.width / 2
        val midY = y + box.height / 2
        if (midX - dx < minX - .001 || midX + dx > maxX + .001)
            overflow("Text frame crosses a physical page side", (2 * dx).toFloat(), 2 * minOf(midX - minX, maxX - midX))
        if (midY - dy < minY - .001 || midY + dy > maxY + .001)
            overflow("Text frame crosses the physical page top or bottom", (2 * dy).toFloat(), 2 * minOf(midY - minY, maxY - midY))
        return x to y
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

    private fun overflow(reason: String, required: Float, available: Float): Nothing =
        throw PdfRenderException(PdfFailureCode.CONTENT_OVERFLOW, "$reason; required=$required pt; available=$available pt")

}
