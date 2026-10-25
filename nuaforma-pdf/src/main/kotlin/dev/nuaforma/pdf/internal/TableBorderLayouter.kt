package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.*
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.roundToInt

internal object TableBorderLayouter {
    private const val tolerance = 0.001f
    private data class Interval(val start: Float, val end: Float, val edge: PositionedTableBorder, val order: Int)
    private fun key(value: Float) = (value * 1_024).roundToInt()

    fun rectangles(elements: List<PositionedElement>, width: Float, height: Float): List<PositionedRectangle> {
        val edges = elements.filterIsInstance<PositionedTableBorder>()
        if (edges.isEmpty()) return emptyList()
        if (edges.size > 20_000) throw PdfRenderException(PdfFailureCode.RESOURCE_LIMIT, "Table border budget exceeded")
        if (edges.any { !it.x.isFinite() || !it.top.isFinite() || !it.length.isFinite() || it.length <= tolerance }) {
            throw PdfRenderException(PdfFailureCode.CONTENT_OVERFLOW, "Invalid table border geometry")
        }
        val result = mutableListOf<PositionedRectangle>()
        for (group in edges.groupBy { it.group }.values) {
            PageLayouter.checkCancellation()
            val resolved = group.groupBy { it.horizontal to key(if (it.horizontal) it.top else it.x) }.values.flatMap(::collapse)
            val vertical = resolved.filter { !it.horizontal }.groupBy { key(it.x) }.mapValues { (_, lines) -> lines.sortedBy { it.top } }
            fun before(lines: List<PositionedTableBorder>, value: Float, x: Boolean): Int {
                var low = 0; var high = lines.size
                while (low < high) { val mid = (low + high) / 2; if ((if (x) lines[mid].x else lines[mid].top) <= value) low = mid + 1 else high = mid }
                return low
            }
            fun verticalWidth(x: Float, y: Float): Float? {
                val lines = vertical[key(x)] ?: return null
                val line = lines.getOrNull(before(lines, y + tolerance, false) - 1) ?: return null
                return if (y <= line.top + line.length + tolerance) line.stroke.border.width else null
            }
            val paintingOrder = compareBy<PositionedTableBorder> { it.stroke.priority }.thenBy { it.stroke.border.width }.thenBy { !it.horizontal }
            val painting = mutableListOf<Triple<PositionedRectangle, PdfEdgeStroke, Int>>()
            for (edge in resolved.sortedWith(paintingOrder)) {
                PageLayouter.checkCancellation()
                val stroke = edge.stroke.border
                val rectangle = if (edge.horizontal) {
                    val left = (verticalWidth(edge.x, edge.top) ?: 0f) / 2
                    val right = verticalWidth(edge.x + edge.length, edge.top)?.let { stroke.width - it / 2 } ?: 0f
                    PositionedRectangle(edge.x - left, edge.top, edge.length + left + right, stroke.width, stroke.color, null)
                } else PositionedRectangle(edge.x - stroke.width / 2, edge.top, stroke.width, edge.length, stroke.color, null)
                if (rectangle.width <= 0 || rectangle.height <= 0 || rectangle.x < -tolerance || rectangle.top < -tolerance ||
                    rectangle.x + rectangle.width > width + tolerance || rectangle.top + rectangle.height > height + tolerance) {
                    throw PdfRenderException(PdfFailureCode.CONTENT_OVERFLOW, "Table border geometry exceeds its page")
                }
                painting += Triple(rectangle, edge.stroke, if (edge.horizontal) 2 else 0)

            }
            result += painting.sortedWith(compareBy<Triple<PositionedRectangle, PdfEdgeStroke, Int>> { it.third }.thenBy { it.second.priority }.thenBy { it.second.border.width }).map { it.first }
        }
        return result
    }

    private fun collapse(edges: List<PositionedTableBorder>): List<PositionedTableBorder> {
        val intervals = edges.mapIndexed { order, edge ->
            val start = if (edge.horizontal) edge.x else edge.top
            Interval(start, start + edge.length, edge, order)
        }.sortedBy { it.start }
        val points = intervals.flatMap { listOf(it.start, it.end) }.distinct().sorted()
        val active = PriorityQueue(compareByDescending<Interval> { it.edge.stroke.priority }.thenByDescending { it.edge.stroke.border.width }.thenBy { it.order })
        val result = mutableListOf<PositionedTableBorder>(); var cursor = 0
        for ((start, end) in points.zipWithNext()) {
            PageLayouter.checkCancellation()
            while (cursor < intervals.size && intervals[cursor].start <= start + tolerance) active += intervals[cursor++]
            while (active.isNotEmpty() && active.peek().end <= start + tolerance) active.remove()
            if (end - start <= tolerance || active.isEmpty()) continue
            val winner = active.peek().edge
            val previous = result.lastOrNull()
            val previousEnd = previous?.let { (if (it.horizontal) it.x else it.top) + it.length }
            if (previous != null && previous.stroke == winner.stroke && abs(previousEnd!! - start) <= tolerance) {
                result[result.lastIndex] = previous.copy(length = end - (if (previous.horizontal) previous.x else previous.top))
            } else result += winner.copy(x = if (winner.horizontal) start else winner.x, top = if (winner.horizontal) winner.top else start, length = end - start)
        }
        return result
    }
}
