package dev.nuaforma.docx.pdf.adapters.nativeengine

/** Separates a picture's painted aspect ratio from the space reserved by the Word drawing. */
internal object WordPictureTransform {
    data class Fit(val width: Float, val height: Float, val blankRight: Float, val blankBottom: Float)

    fun fit(outerWidth: String?, outerHeight: String?, innerWidth: String?, innerHeight: String?): Fit? {
        fun dimension(value: String?): Double? = value?.toLongOrNull()?.takeIf { it in 12_700L..182_880_000L }?.div(12_700.0)
        val boxWidth = dimension(outerWidth) ?: return null
        val boxHeight = dimension(outerHeight) ?: return null
        val sourceWidth = dimension(innerWidth) ?: return null
        val sourceHeight = dimension(innerHeight) ?: return null
        if (sourceWidth / boxWidth !in .9..1.1 || sourceHeight / boxHeight !in .9..1.1) return null
        val scale = minOf(boxWidth / sourceWidth, boxHeight / sourceHeight)
        val width = minOf(boxWidth, sourceWidth * scale)
        val height = minOf(boxHeight, sourceHeight * scale)
        if (width < 1.0 || height < 1.0) return null
        return Fit(width.toFloat(), height.toFloat(), (boxWidth - width).toFloat(), (boxHeight - height).toFloat())
    }
}
