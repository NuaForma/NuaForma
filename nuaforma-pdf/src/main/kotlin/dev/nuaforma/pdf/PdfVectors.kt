package dev.nuaforma.pdf

import java.util.Collections

/** Atomic inline opaque vector. Size is in points; the bottom edge sits on the text baseline. */
data class PdfVectorRun(
    val vector: PdfVector,
    val width: Float,
    val height: Float,
    val color: PdfColor,
) : PdfInline {
    init {
        require(width.isFinite() && width in 0.1f..1000f && height.isFinite() && height in 0.1f..1000f) {
            "Invalid inline vector size"
        }
        require(kotlin.math.abs(width - height) < .001f) { "Stroked inline vectors require a square view box" }
    }
}

/** Bounded opaque polylines with round caps/joins; independent of template and provider contracts. */
class PdfVector @JvmOverloads constructor(paths: List<PdfVectorPath>, val strokeWidth: Int = 70) {
    val paths: List<PdfVectorPath> = Collections.unmodifiableList(ArrayList(paths))

    init {
        require(strokeWidth in 10..100) { "Invalid vector stroke width" }
        require(this.paths.size in 1..8 && this.paths.sumOf { it.points.size } <= 64) { "Vector geometry budget exceeded" }
        val margin = (strokeWidth + 1) / 2
        require(this.paths.all { path -> path.points.all {
            it.x in margin..1000 - margin && it.y in margin..1000 - margin
        } }) { "Vector stroke exceeds its view box" }
    }
}

data class PdfVectorPoint(val x: Int, val y: Int) {
    init {
        require(x in 0..1000 && y in 0..1000) { "Invalid vector point" }
    }
}

class PdfVectorPath @JvmOverloads constructor(points: List<PdfVectorPoint>, val closed: Boolean = false) {
    val points: List<PdfVectorPoint> = Collections.unmodifiableList(ArrayList(points))

    init {
        require(this.points.size in (if (closed) 3 else 2)..16) { "Invalid vector path length" }
        require(this.points.zipWithNext().all { (a, b) -> a != b }) { "Degenerate vector segment" }
        require(!closed || this.points.first() != this.points.last()) { "Closed paths close implicitly" }
    }
}
