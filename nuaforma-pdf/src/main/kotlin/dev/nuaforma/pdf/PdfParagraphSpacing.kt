package dev.nuaforma.pdf

/** Conditional paragraph gaps, independently of the source format and physical gap sizes. */
data class PdfParagraphSpacing @JvmOverloads constructor(
    val suppressBeforeAtAreaStart: Boolean = false,
    val suppressBeforeAtForcedPageStart: Boolean = false,
    val suppressAfterAtCellEnd: Boolean = false,
)

enum class PdfPageSide { ODD, EVEN }

/** Source-resolved translation applied after physical pagination, including section transitions. */
data class PdfParagraphPageOffset(val x: Float, val side: PdfPageSide) {
    init { require(x.isFinite() && x in -14_400f..14_400f) { "Invalid alternating paragraph offset" } }
}
