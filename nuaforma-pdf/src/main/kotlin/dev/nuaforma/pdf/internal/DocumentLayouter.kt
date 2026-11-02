package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.*

internal data class DocumentLayout(
    val pages: List<PageLayout>, val displayNumbers: List<Int>,
    val structure: PdfLogicalStructure? = null, val identities: List<List<String>> = emptyList(),
    val fieldValues: Map<String, String> = emptyMap(),
    val sequenceCounts: Map<String, Int> = emptyMap(),
)

/** Body pagination and page-band resolution shared by ordinary and iterated documents. */
internal object DocumentLayouter {
    private data class Pass(val layout: DocumentLayout, val sequences: List<Triple<Int, Int, Int>>)

    fun plain(document: PdfDocument, measurer: TextMeasurer, control: PdfRenderControl): DocumentLayout {
        if (document.pageSequences.none { sequence -> sequence.flow?.bands.orEmpty().any { it.reserveContentSpace } })
            return decorate(plainPass(document, measurer, control, null), document, measurer)
        var estimate: DocumentLayout? = null
        repeat(8) {
            control.checkActive()
            val actual = plainPass(document, measurer, control, estimate)
            val stable = document.pageSequences.indices.all { index ->
                val (offset, _, start) = actual.sequences[index]
                PageBandReservation.page(document.pageSequences[index], index, start, actual.layout, measurer) == actual.layout.pages[offset].page
            }
            if (stable) return decorate(actual, document, measurer)
            estimate = actual.layout
        }
        throw PdfRenderException(PdfFailureCode.CONTENT_OVERFLOW, "Page-band reservation did not converge")
    }

    private fun plainPass(document: PdfDocument, measurer: TextMeasurer, control: PdfRenderControl, estimate: DocumentLayout?): Pass {
        val layouts = mutableListOf<PageLayout>()
        val displayNumbers = mutableListOf<Int>()
        val sequences = mutableListOf<Triple<Int, Int, Int>>()
        var next = 1
        for ((sequenceIndex, sequence) in document.pageSequences.withIndex()) {
            control.checkActive()
            val start = sequence.numbering.start ?: next
            if (sequences.isNotEmpty() && sequence.numbering.alignParity && start % 2 != next % 2) {
                layouts += PageLayout(sequence.page, emptyList())
                displayNumbers += 0
            }
            val remaining = document.maxPages - layouts.size
            if (remaining <= 0) limit("Document page budget exceeded")
            val page = PageBandReservation.page(sequence, sequenceIndex, start, estimate, measurer)
            (sequence.blocks.filterIsInstance<PdfParagraph>() + document.notes.flatMap { it.paragraphs }).forEach { ParagraphBounds.check(it, page) }
            val single = PdfDocument(page, sequence.blocks, sequence.flow, document.referenceTargets, document.notes)
            val pages = when {
                document.notes.isNotEmpty() -> NoteFlowLayouter.layout(single, measurer, remaining, control)
                sequence.flow == null -> listOf(PageLayouter.layout(single, measurer))
                else -> FlowLayouter.layout(single, measurer, remaining)
            }
            if (start !in 0..9_999 || start + pages.size - 1 > 9_999) limit("Page-number range exceeded")
            sequences += Triple(layouts.size, pages.size, start)
            layouts += pages
            displayNumbers += pages.indices.map { start + it }
            next = start + pages.size
        }
        return Pass(DocumentLayout(layouts, displayNumbers,
            sequenceCounts = sequences.withIndex().associate { (index, entry) -> "sequence$index" to entry.second }), sequences)
    }

    private fun decorate(pass: Pass, document: PdfDocument, measurer: TextMeasurer): DocumentLayout {
        val layouts = pass.layout.pages.toMutableList()
        for ((index, sequence) in document.pageSequences.withIndex()) {
            val (offset, count, start) = pass.sequences[index]
            sequence.flow?.let { policy ->
                val decorated = FlowLayouter.decorate(layouts.subList(offset, offset + count), policy, start, layouts.size, measurer)
                for ((local, layout) in decorated.withIndex()) layouts[offset + local] = layout
            }
        }
        return pass.layout.copy(pages = layouts.mapIndexed { index, layout -> ParagraphPageOffsets.resolve(layout, index + 1) })
    }

    fun texts(elements: List<PositionedElement>): Sequence<PositionedText> = elements.asSequence().flatMap {
        when (it) { is PositionedText -> sequenceOf(it); is PositionedBox -> texts(it.elements); else -> emptySequence() }
    }

    fun placedTargets(layout: DocumentLayout): Map<String, Int> {
        val result = linkedMapOf<String, Int>()
        for ((index, page) in layout.pages.withIndex()) for (text in texts(page.elements)) if (text.text.isNotBlank()) {
            text.logicalOwner?.let { result.putIfAbsent(it, layout.displayNumbers[index]) }
            text.span?.let { result.putIfAbsent(it.id, layout.displayNumbers[index]) }
            text.navigation?.destinations?.forEach { result.putIfAbsent(it.name, layout.displayNumbers[index]) }
        }
        return result
    }

    fun validatePlacedLinks(layout: DocumentLayout) {
        val destinations = layout.pages.flatMap { texts(it.elements).toList() }.filter { it.text.isNotBlank() }
            .flatMap { it.navigation?.destinations.orEmpty() }.map { it.name }.toSet()
        for (page in layout.pages) for (text in texts(page.elements)) {
            val target = (text.navigation?.link as? PdfLink.Internal)?.destination ?: continue
            if (target !in destinations) throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Internal navigation target is not placed")
        }
    }

    private fun limit(message: String): Nothing = throw PdfRenderException(PdfFailureCode.RESOURCE_LIMIT, message)

}
