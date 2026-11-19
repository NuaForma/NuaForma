package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.PdfFailureCode
import dev.nuaforma.pdf.PdfRenderException

internal object TextBackgrounds {

    fun rectangle(text: PositionedText, width: Float, height: Float): PositionedRectangle? {
        val color = text.style.background ?: return null
        val background = text.background ?: return null
        val x = text.x + background.offset
        val top = text.baseline - background.ascent
        if (x < -.001f) reject("Text background exceeds physical left boundary", -x, 0f)
        if (x + background.width > width + .001f) reject("Text background exceeds physical right boundary", background.width, width - x)
        if (top < -.001f) reject("Text background exceeds physical top boundary", -top, 0f)
        if (top + background.height > height + .001f) reject("Text background exceeds physical bottom boundary", background.height, height - top)
        return PositionedRectangle(x, top, background.width, background.height, color, null)
    }

    private fun reject(reason: String, required: Float, available: Float): Nothing =
        throw PdfRenderException(PdfFailureCode.CONTENT_OVERFLOW, "$reason; required=$required pt; available=$available pt")

}
