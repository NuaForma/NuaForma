package dev.nuaforma.pdf

/** A multiple of the supplied font's natural line box; independent of any source format. */
data class PdfAutomaticLineSpacing @JvmOverloads constructor(val multiple: Float = 1f) {
    init { require(multiple.isFinite() && multiple in 1f..10f) { "Invalid automatic line multiple" } }
}
