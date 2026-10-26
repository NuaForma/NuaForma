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
    fun plain(document: PdfDocument, measurer: TextMeasurer, control: PdfRenderControl): DocumentLayout {
        val layouts = mutableListOf<PageLayout>()
        val displayNumbers = mutableListOf<Int>()
        val sequences = mutableListOf<Triple<Int, Int, Int>>()
        var next = 1
        for (sequence in document.pageSequences) {
            control.checkActive()
            val start = sequence.numbering.start ?: next
            if (sequences.isNotEmpty() && sequence.numbering.alignParity && start % 2 != next % 2) {
                layouts += PageLayout(sequence.page, emptyList())
                displayNumbers += 0
            }
            val remaining = document.maxPages - layouts.size
            if (remaining <= 0) limit("Document page budget exceeded")
            val single = PdfDocument(sequence.page, sequence.blocks, sequence.flow, document.referenceTargets, document.notes)
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
        for ((index, sequence) in document.pageSequences.withIndex()) {
            val (offset, count, start) = sequences[index]
            sequence.flow?.let { policy ->
                val decorated = FlowLayouter.decorate(layouts.subList(offset, offset + count), policy, start, layouts.size, measurer)
                for ((local, layout) in decorated.withIndex()) layouts[offset + local] = layout
            }
        }
        return DocumentLayout(layouts, displayNumbers, sequenceCounts = sequences.withIndex().associate { (index, entry) -> "sequence$index" to entry.second })
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
