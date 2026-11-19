package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.*

/** Join only neighboring shaded paragraph fragments that actually occupy the same canvas. */
internal object ParagraphBackgrounds {

    fun connect(elements: List<PositionedElement>): List<PositionedElement> {
        val first = java.util.IdentityHashMap<PdfParagraph, PositionedRectangle>()
        val replacements = java.util.IdentityHashMap<PositionedRectangle, PositionedRectangle>()
        for (fill in elements.filterIsInstance<PositionedRectangle>()) if (fill.first && fill.paragraph != null)
            first[fill.paragraph] = fill
        for (fill in elements.filterIsInstance<PositionedRectangle>()) {
            PageLayouter.checkCancellation()
            val following = fill.following ?: continue
            val next = first[following] ?: continue
            if (fill.pageOffset != null || next.pageOffset != null || fill.paragraph?.borders != following.borders ||
                fill.paragraph?.borders?.connectAdjacent == false) continue
            val visible = clipped(fill) ?: continue
            val nextVisible = clipped(next) ?: continue
            if (kotlin.math.abs(visible.x - nextVisible.x) > .001f || kotlin.math.abs(visible.width - nextVisible.width) > .001f) continue
            val end = fill.top + fill.height
            if (next.top < end - .001f) continue
            val join = minOf(next.top, end + (fill.paragraph?.spaceAfter ?: 0f))
            val own = replacements[fill] ?: fill
            replacements[fill] = own.copy(height = join - own.top)
            val other = replacements[next] ?: next
            replacements[next] = other.copy(top = join, height = other.top + other.height - join)
        }
        return elements.map { if (it is PositionedRectangle) replacements[it] ?: it else it }
    }

    fun validate(fill: PositionedRectangle, width: Float, height: Float) {
        if (fill.x < -.001f) reject("left", -fill.x, 0f)
        if (fill.top < -.001f) reject("top", -fill.top, 0f)
        if (fill.x + fill.width > width + .001f) reject("right", fill.width, width - fill.x)
        if (fill.top + fill.height > height + .001f) reject("bottom", fill.height, height - fill.top)
    }

    fun clipped(fill: PositionedRectangle): PositionedRectangle? {
        val bounds = fill.clip ?: return fill
        val left = maxOf(fill.x, bounds.left)
        val top = maxOf(fill.top, bounds.top)
        val right = minOf(fill.x + fill.width, bounds.right)
        val bottom = minOf(fill.top + fill.height, bounds.bottom)
        if (left >= right || top >= bottom) return null
        if (left == fill.x && top == fill.top && right == fill.x + fill.width && bottom == fill.top + fill.height) return fill
        return fill.copy(x = left, top = top, width = right - left, height = bottom - top)
    }

    private fun reject(edge: String, required: Float, available: Float): Nothing =
        throw PdfRenderException(PdfFailureCode.CONTENT_OVERFLOW,
            "Paragraph background exceeds physical $edge boundary; required=$required pt; available=$available pt")

}
