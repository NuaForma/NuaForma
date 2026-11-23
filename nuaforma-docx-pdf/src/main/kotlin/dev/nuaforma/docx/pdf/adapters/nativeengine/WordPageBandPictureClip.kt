package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.pdf.PdfImage
import dev.nuaforma.pdf.PdfPage

internal object WordPageBandPictureClip {
    data class VisiblePicture(val image: PdfImage, val left: Float, val top: Float)

    fun clip(image: PdfImage, left: Float, top: Float, page: PdfPage): VisiblePicture? {
        val visibleLeft = left.coerceAtLeast(0f)
        val visibleTop = top.coerceAtLeast(0f)
        val right = left + image.width
        val bottom = top + image.height
        val visibleRight = right.coerceAtMost(page.width)
        val visibleBottom = bottom.coerceAtMost(page.height)
        val width = visibleRight - visibleLeft
        val height = visibleBottom - visibleTop
        if (width < 1f || height < 1f) return null

        val visible = image.withPhysicalCrop(visibleLeft - left, visibleTop - top, right - visibleRight, bottom - visibleBottom)
        return VisiblePicture(visible, visibleLeft, visibleTop)
    }
}
