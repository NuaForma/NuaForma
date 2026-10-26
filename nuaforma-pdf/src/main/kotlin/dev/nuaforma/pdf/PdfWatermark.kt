package dev.nuaforma.pdf

/** Single-line background text, centered on each physical page with clockwise rotation. */
class PdfWatermark @JvmOverloads constructor(
    val text: String,
    val fontId: String,
    val color: PdfColor = PdfColor(176, 176, 176),
    val opacity: Float = 0.25f,
    val rotation: Float = -45f,
) {
    init {
        require(text.isNotBlank() && text.length <= 4096 && text.codePoints().allMatch { it >= 32 && it !in 0xD800..0xDFFF && it !in 0xFFFE..0xFFFF }) { "Invalid watermark text" }
        require(fontId.isNotBlank() && fontId.length <= 128) { "Invalid watermark font identity" }
        require(opacity.isFinite() && opacity in 0f..1f && rotation.isFinite() && rotation in -360f..360f) { "Invalid watermark appearance" }
    }
}
