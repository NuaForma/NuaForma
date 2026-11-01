package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.PdfParagraph

internal object ParagraphSpacing {

    fun before(p: PdfParagraph, previousAfter: Float, atStart: Boolean = false, forcedPageStart: Boolean = false): Float {
        val suppressed = atStart && (if (forcedPageStart) p.spacing.suppressBeforeAtForcedPageStart else p.spacing.suppressBeforeAtAreaStart)
        val gap = if (suppressed) 0f else p.spaceBefore
        return if (p.format.collapseSpacing) maxOf(0f, gap - previousAfter) else gap
    }

    fun after(p: PdfParagraph, atCellEnd: Boolean = false): Float =
        if (atCellEnd && p.spacing.suppressAfterAtCellEnd) 0f else p.spaceAfter

}
