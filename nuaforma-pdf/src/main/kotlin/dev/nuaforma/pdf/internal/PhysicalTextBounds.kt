package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.*

/** Bounds that depend on flow placement are checked in physical page coordinates. */
internal object PhysicalTextBounds {

    fun check(page: PageLayout, measurer: TextMeasurer) {
        for (text in page.elements.filterIsInstance<PositionedText>()) {
            val overhang = text.physicalLeftOverhang
            if (!text.requiresPhysicalBounds && overhang == null) continue
            PageLayouter.checkCancellation()
            if (overhang != null && text.x < -.001f)
                throw PdfRenderException(PdfFailureCode.CONTENT_OVERFLOW,
                    "List marker exceeds physical left page boundary; required=$overhang pt; available=${text.x + overhang} pt")
            if (!text.requiresPhysicalBounds) continue
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
