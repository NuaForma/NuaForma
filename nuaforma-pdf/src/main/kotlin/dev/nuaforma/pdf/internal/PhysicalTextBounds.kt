package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.*

/** Exact-leading measurements are checked only after placement on the physical page. */
internal object PhysicalTextBounds {

    fun check(page: PageLayout, measurer: TextMeasurer) {
        for (text in page.elements.filterIsInstance<PositionedText>().filter { it.requiresPhysicalBounds }) {
            PageLayouter.checkCancellation()
            if (text.text.isBlank() && !text.style.underline && !text.style.strikethrough) continue
            val metrics = measurer.ink(text.text, text.style)
            val top = text.baseline - metrics.ascent
            val bottom = text.baseline + metrics.descent
            if (!top.isFinite() || !bottom.isFinite())
                throw PdfRenderException(PdfFailureCode.CONTENT_OVERFLOW, "Text ink bounds are not finite")
            if (top < -.01f)
                throw PdfRenderException(PdfFailureCode.CONTENT_OVERFLOW,
                    "Text ascent exceeds physical page top; required=${metrics.ascent} pt; available=${text.baseline} pt")
            if (bottom > page.page.height + .01f)
                throw PdfRenderException(PdfFailureCode.CONTENT_OVERFLOW,
                    "Text descent exceeds physical page bottom; required=${metrics.descent} pt; available=${page.page.height - text.baseline} pt")
        }
    }
}
