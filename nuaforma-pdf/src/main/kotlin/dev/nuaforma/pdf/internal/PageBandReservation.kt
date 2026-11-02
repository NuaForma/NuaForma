package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.*

/** Reuses ordinary band measurement before body/footnote pagination; field values converge with page counts. */
internal object PageBandReservation {

    fun page(sequence: PdfPageSequence, index: Int, start: Int, estimate: DocumentLayout?, measurer: TextMeasurer): PdfPage {
        val flow = sequence.flow ?: return sequence.page
        if (flow.bands.none { it.reserveContentSpace }) return sequence.page
        val count = estimate?.sequenceCounts?.get("sequence$index") ?: 1
        val total = estimate?.pages?.size ?: 1
        var top = sequence.page.margins.top
        var bottom = sequence.page.margins.bottom
        for (local in 0 until count) {
            PageLayouter.checkCancellation()
            val selected = when {
                local == 0 && flow.firstPageBands != null -> flow.firstPageBands
                (start + local) % 2 == 0 && flow.evenPageBands != null -> flow.evenPageBands
                else -> PdfPageBands(flow.header, flow.footer)
            }
            selected.header?.takeIf { it.reserveContentSpace }?.let {
                top = maxOf(top, it.offset + FlowLayouter.bandContent(it, sequence.page, start + local, total, count, measurer).second)
            }
            selected.footer?.takeIf { it.reserveContentSpace }?.let {
                bottom = maxOf(bottom, it.offset + FlowLayouter.bandContent(it, sequence.page, start + local, total, count, measurer).second)
            }
        }
        if (top + bottom >= sequence.page.height)
            throw PdfRenderException(PdfFailureCode.CONTENT_OVERFLOW, "Page bands leave no body area")
        return sequence.page.copy(margins = sequence.page.margins.copy(top = top, bottom = bottom))
    }
}
