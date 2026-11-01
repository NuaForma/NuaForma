package dev.nuaforma.pdf

enum class PdfKerning { NONE, PAIRS, OPENTYPE }

/** Explicit glyph selection/positioning policy; source adapters resolve thresholds and compatibility. */
data class PdfFontFeatures @JvmOverloads constructor(
    val kerning: PdfKerning = PdfKerning.NONE,
    val standardLigatures: Boolean = false,
) {
    internal val active: Boolean get() = kerning != PdfKerning.NONE || standardLigatures
}
