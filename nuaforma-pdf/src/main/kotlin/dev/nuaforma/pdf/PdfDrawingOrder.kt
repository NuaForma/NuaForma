package dev.nuaforma.pdf

/** Physical drawing layers surrounding ordinary flowing content. */
enum class PdfDrawingLayer { BACKGROUND, FOREGROUND }

/** Lower values paint first within a layer; equal keys retain their input order. */
data class PdfDrawingOrder @JvmOverloads constructor(
    val layer: PdfDrawingLayer, val stackingOrder: Long = 0, val tieBreak: Long = 0,
) {
    init {
        require(stackingOrder in 0L..4_294_967_295L && tieBreak in 0L..4_294_967_295L) { "Invalid drawing order" }
    }
}
