package dev.nuaforma.pdf

/** Rectangular text exclusion, resolved by an adapter without source-format types. */
enum class PdfImageWrap { NONE, TOP_BOTTOM, LEFT, RIGHT, LARGEST, BOTH }

/** Physical page x; y is relative to either the page or the associated paragraph. */
data class PdfAnchoredImage @JvmOverloads constructor(
    val image: PdfImage, val left: Float, val top: Float,
    val anchor: PdfBoxAnchor = PdfBoxAnchor.PARAGRAPH,
    val wrap: PdfImageWrap = PdfImageWrap.TOP_BOTTOM,
    val distances: PdfMargins = PdfMargins(0f, 0f, 0f, 0f),
) {
    init {
        require(left.isFinite() && top.isFinite() && left in 0f..14_400f && top in 0f..14_400f) { "Invalid anchored image position" }
        require(listOf(distances.top, distances.right, distances.bottom, distances.left).all { it <= 14_400f }) { "Invalid image wrapping distance" }
    }
}

/** Fractions removed from each source edge before stretching into the declared physical rectangle. */
data class PdfImageCrop @JvmOverloads constructor(val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f, val left: Float = 0f) {
    init {
        require(listOf(top, right, bottom, left).all { it.isFinite() && it in 0f..<1f } && top + bottom < 1f && left + right < 1f) { "Invalid image crop" }
    }
}
