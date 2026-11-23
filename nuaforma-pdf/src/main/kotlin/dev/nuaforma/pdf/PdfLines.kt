package dev.nuaforma.pdf

/** Opaque vector strokes, expressed in PDF points independently of the source format. */
enum class PdfLineStyle { SOLID, DASHED, DOTTED, DOUBLE, THIN_THICK_SMALL_GAP, THICK_THIN_SMALL_GAP }

internal const val COMPOUND_BORDER_SMALL_GAP = .75f

data class PdfLineStroke @JvmOverloads constructor(
    val width: Float = 1f,
    val color: PdfColor = PdfColor(0, 0, 0),
    val style: PdfLineStyle = PdfLineStyle.SOLID,
    val dashOn: Float? = null,
    val dashOff: Float? = null,
) {
    init {
        require(width.isFinite() && width in .1f..4f) { "Invalid line width" }
        require(dashOn == null && dashOff == null || style in setOf(PdfLineStyle.DASHED, PdfLineStyle.DOTTED) &&
            dashOn != null && dashOff != null && dashOn.isFinite() && dashOff.isFinite() && dashOn in .1f..14_400f && dashOff in .1f..14_400f) { "Invalid line dash pattern" }
    }
    val extent: Float get() = when (style) {
        PdfLineStyle.DOUBLE -> 3 * width
        PdfLineStyle.THIN_THICK_SMALL_GAP, PdfLineStyle.THICK_THIN_SMALL_GAP -> width + 2 * COMPOUND_BORDER_SMALL_GAP
        else -> width
    }
}

data class PdfParagraphEdge @JvmOverloads constructor(val stroke: PdfLineStroke, val space: Float = 0f) {
    init { require(space.isFinite() && space in 0f..31f) { "Invalid paragraph border spacing" } }
}

/** Select physical pages within a page sequence, independent of their printed numbering. */
enum class PdfPageFrameDisplay { ALL, FIRST, EXCEPT_FIRST }

/** A uniform solid page frame. Offsets measure from each physical page edge to the outer ink. */
data class PdfPageFrame @JvmOverloads constructor(
    val offsets: PdfMargins,
    val border: PdfBorder,
    val display: PdfPageFrameDisplay = PdfPageFrameDisplay.ALL,
)

/** Equal borders and indents on consecutive paragraphs form one frame. */
data class PdfParagraphBorders @JvmOverloads constructor(
    val top: PdfParagraphEdge? = null,
    val right: PdfParagraphEdge? = null,
    val bottom: PdfParagraphEdge? = null,
    val left: PdfParagraphEdge? = null,
    val between: PdfParagraphEdge? = null,
    val connectAdjacent: Boolean = true,
)

/** A non-wrapping line anchored to the paragraph's outer origin or physical page axes. */
data class PdfLineDrawing @JvmOverloads constructor(
    val fromX: Float, val fromY: Float, val toX: Float, val toY: Float,
    val stroke: PdfLineStroke = PdfLineStroke(),
    val pageX: Boolean = false, val pageY: Boolean = false,
    val behindText: Boolean = true,
) {
    init {
        require(listOf(fromX, fromY, toX, toY).all { it.isFinite() && it in -14_400f..14_400f }) { "Invalid line coordinates" }
        require(fromX != toX || fromY != toY) { "A line requires distinct endpoints" }
    }
}
