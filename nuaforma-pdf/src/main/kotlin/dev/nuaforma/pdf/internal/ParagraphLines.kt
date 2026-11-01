package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.*

/** Request-local geometry. Border spacing is separate from collapsible paragraph spacing. */
internal object ParagraphLines {
    data class Frame(val borders: PdfParagraphBorders?, val previous: Boolean, val next: Boolean, val owner: PdfParagraph, val following: PdfParagraph?) {
        val before: Float get() = if (previous) 0f else borders?.top?.let { it.space + it.stroke.extent } ?: 0f
        val after: Float get() = if (next) borders?.between?.let { 2 * it.space + it.stroke.extent } ?: 0f
            else borders?.bottom?.let { it.space + it.stroke.extent } ?: 0f
    }

    fun frame(p: PdfParagraph, previous: PdfParagraph? = null, next: PdfParagraph? = null, owner: PdfParagraph = p): Frame {

        fun matches(other: PdfParagraph?) = p.borders != null && p.borders.connectAdjacent && other?.borders == p.borders &&
            other.format.leftIndent == p.format.leftIndent && other.format.rightIndent == p.format.rightIndent && other.pageOffset == p.pageOffset

        return Frame(p.borders, matches(previous) && !p.format.pageBreakBefore, matches(next) && next?.format?.pageBreakBefore != true, owner, next)
    }

    fun decorate(p: PdfParagraph, raw: List<List<PositionedElement>>, height: Float, width: Float,
        previous: PdfParagraph?, next: PdfParagraph?, owner: PdfParagraph = p): FlowLayouter.MeasuredParagraph {
        val frame = frame(p, previous, next, owner)
        val heights = raw.indices.map { height + (if (it == 0) frame.before else 0f) + (if (it == raw.lastIndex) frame.after else 0f) }
        val lines = raw.mapIndexed { index, elements ->
            val inset = if (index == 0) frame.before else 0f
            val marks = mutableListOf<PositionedElement>()
            strokes(p, frame, width, inset, height, index == 0, index == raw.lastIndex, marks)
            marks.map { ParagraphPageOffsets.mark(it, p.pageOffset) } + elements.map { FlowLayouter.translate(it, 0f, inset) }
        }
        return FlowLayouter.MeasuredParagraph(p, lines, height, heights)
    }

    fun strokes(p: PdfParagraph, frame: Frame, width: Float, textTop: Float, height: Float,
        first: Boolean, last: Boolean, target: MutableList<PositionedElement>) {
        val b = frame.borders
        val left = p.format.leftIndent - 1.5f
        val right = width - p.format.rightIndent + 1.5f
        val outerLeft = left - (b?.left?.let { it.space + it.stroke.extent } ?: 0f)
        val outerRight = right + (b?.right?.let { it.space + it.stroke.extent } ?: 0f)
        val a = textTop - if (first) frame.before else 0f
        val z = textTop + height + if (last) frame.after + if (frame.next) p.spaceAfter else 0f else 0f

        fun horizontal(edge: PdfParagraphEdge?, y: Float) {
            edge ?: return
            target += PositionedStroke(outerLeft, y, outerRight, y, edge.stroke)
        }

        if (first && !frame.previous) b?.top?.let { horizontal(it, textTop - it.space - it.stroke.extent / 2) }
        if (last) {
            if (frame.next) b?.between?.let { horizontal(it, textTop + height + p.spaceAfter + it.space + it.stroke.extent / 2) }
            else b?.bottom?.let { horizontal(it, textTop + height + it.space + it.stroke.extent / 2) }
        }

        fun side(edge: PdfParagraphEdge, x: Float) {
            target += PositionedStroke(x, a, x, z, edge.stroke,
                joinOwner = if (first && frame.previous) frame.owner else null,
                joinFollowing = if (last && frame.next) frame.following else null)
        }

        b?.left?.let { side(it, left - it.space - it.stroke.extent / 2) }
        b?.right?.let { side(it, right + it.space + it.stroke.extent / 2) }
        if (first) p.lines.forEach { line ->
            val origin = if (line.pageY) 0f else textTop - frame.before
            target += PositionedStroke(line.fromX, line.fromY + origin,
                line.toX, line.toY + origin, line.stroke, line.pageX, line.pageY,
                if (line.behindText) StrokeLayer.BACKGROUND else StrokeLayer.FOREGROUND)
        }
    }

    /** Connect only actual neighboring fragments on the same canvas, after pagination is final. */
    fun connect(elements: List<PositionedElement>): List<PositionedElement> {
        val starts = java.util.IdentityHashMap<PdfParagraph, MutableList<PositionedStroke>>()
        elements.filterIsInstance<PositionedStroke>().forEach { stroke ->
            stroke.joinOwner?.let { starts.getOrPut(it) { mutableListOf() } += stroke }
        }
        if (starts.isEmpty()) return elements.toList()
        return elements.map { element ->
            PageLayouter.checkCancellation()
            if (element !is PositionedStroke || element.joinFollowing == null) element else {
                val next = starts[element.joinFollowing].orEmpty().asSequence().filter {
                    it.x == element.x && it.endX == element.endX && it.stroke == element.stroke && it.top >= element.endY - .001f
                }.minByOrNull { it.top }
                if (next != null && next.top > element.endY) element.copy(endY = next.top) else element
            }
        }
    }

}
