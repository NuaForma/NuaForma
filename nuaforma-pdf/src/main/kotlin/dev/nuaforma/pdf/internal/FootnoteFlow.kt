package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.*

/** A transactional, request-local page reservation. A rejected body placement cannot consume a note. */
internal class FootnoteFlow(document: PdfDocument, private val measurer: TextMeasurer, private val control: PdfRenderControl) {
    private data class Line(val elements: List<PositionedElement>, val height: Float, val before: Float,
        val paragraph: Int, val index: Int, val count: Int, val format: PdfParagraphFormat)
    private data class Measured(val note: PdfNote, val lines: List<Line>)
    private data class Pending(val measured: Measured, val offset: Int)
    private data class Slice(val pending: Pending, val count: Int)
    private data class Plan(val all: List<Pending>, val slices: List<Slice>, val remaining: List<Pending>,
        val height: Float, val continuation: Boolean, val required: Set<String>)
    private val page = document.page
    private val width = page.width - page.margins.left - page.margins.right
    private val bottom = page.height - page.margins.bottom
    private val definitions = document.notes.filter { it.kind == PdfNoteKind.FOOTNOTE }.associate { it.id to measure(it) }
    private val calls = document.allParagraphs().flatMap { it.runs }.filterIsInstance<PdfTextRun>()
        .filter { it.semantics?.role == PdfSemanticRole.NOTE_REFERENCE && it.semantics.target in definitions }
        .associate { requireNotNull(it.semantics).id to requireNotNull(it.semantics.target) }
    private val assigned = mutableSetOf<String>()
    private var pending = emptyList<Pending>()
    private var plan: Plan? = null
    private var requiredHere = emptySet<String>()
    val bodyBottom: Float get() = bottom - (plan?.height ?: 0f)
    val hasPending: Boolean get() = pending.isNotEmpty()

    init {
        if (definitions.values.sumOf { it.lines.size } > 20_000) limit()
        for (definition in definitions.values) checkWidth(definition.note.separator)
    }

    private fun measure(note: PdfNote): Measured {
        val lines = mutableListOf<Line>()
        var previousAfter = 0f
        for ((index, paragraph) in note.paragraphs.withIndex()) {
            control.checkActive()
            if (paragraph.format.pageBreakBefore) unsupported("Explicit page breaks inside notes are not qualified")
            val measured = FlowLayouter.paragraph(paragraph, width, measurer)
            val before = if (paragraph.format.collapseSpacing) maxOf(0f, paragraph.spaceBefore - previousAfter) else paragraph.spaceBefore
            measured.lines.forEachIndexed { line, elements ->
                val gap = if (line == 0) before else 0f
                lines += Line(elements, gap + measured.lineHeight + if (line == measured.lines.lastIndex) paragraph.spaceAfter else 0f,
                    gap, index, line, measured.lines.size, paragraph.format)
            }
            previousAfter = paragraph.spaceAfter
        }
        if (lines.isEmpty()) unsupported("A note requires visible line content")
        return Measured(note, lines)
    }

    /** Greedy note allocation has priority once its owning protected body lines fit. */
    fun accept(end: Float, ids: Set<String>): Boolean {
        control.checkActive()
        val fresh = ids.filter { it in calls && it !in assigned }
        val existing = plan
        if (fresh.isEmpty() && existing != null) return end <= bodyBottom + .001f
        val additions = fresh.map { Pending(definitions.getValue(calls.getValue(it)), 0) }
        val all = pending + additions
        val required = requiredHere + additions.map { it.measured.note.id }
        val candidate = choose(all, end, required) ?: return false
        pending = all
        plan = candidate
        requiredHere = required
        assigned += fresh
        return true
    }

    private fun validCut(entry: Pending, exclusive: Int): Boolean {
        val lines = entry.measured.lines
        if (exclusive == lines.size) return true
        val last = lines[exclusive - 1]
        val next = lines[exclusive]
        if (last.paragraph != next.paragraph) return !last.format.keepWithNext
        if (last.format.keepLines || last.format.keepWithNext) return false
        if (!last.format.widowControl) return true
        val paragraphStart = exclusive - last.index - 1
        return exclusive - maxOf(entry.offset, paragraphStart) >= 2 && last.count - last.index - 1 >= 2
    }

    private fun choose(all: List<Pending>, end: Float, required: Set<String>): Plan? {
        if (all.isEmpty()) return if (end <= bottom + .001f) Plan(all, emptyList(), emptyList(), 0f, false, required) else null
        val policy = all.first().measured.note.separator
        val capacity = bottom - end - policy.height
        if (capacity <= 0f) return null
        var height = 0f
        val slices = mutableListOf<Slice>()
        val remaining = mutableListOf<Pending>()
        var exhausted = false
        for (entry in all) {
            control.checkActive()
            if (exhausted) { remaining += entry; continue }
            var used = entry.offset
            var consumed = 0f
            val lines = entry.measured.lines
            while (used < lines.size && height + consumed + lines[used].height <= capacity + .001f) {
                consumed += lines[used].height; used++
            }
            while (used > entry.offset && !validCut(entry, used)) { used--; consumed -= lines[used].height }
            if (used > entry.offset) { slices += Slice(entry, used - entry.offset); height += consumed }
            if (used < lines.size) { remaining += Pending(entry.measured, used); exhausted = true }
        }
        if (slices.isEmpty() || !slices.map { it.pending.measured.note.id }.toSet().containsAll(required)) return null
        return Plan(all, slices, remaining, height + policy.height, all.first().offset > 0, required)
    }

    /** Paint the committed page once, then transfer only unconsumed logical lines to the next page. */
    fun finish(target: MutableList<PositionedElement>) {
        control.checkActive()
        val selected = plan ?: choose(pending, page.margins.top, requiredHere)
        if (selected == null && pending.isNotEmpty()) overflow()
        if (selected != null && selected.slices.isNotEmpty()) {
            val policy = selected.slices.first().pending.measured.note.separator
            val top = bottom - selected.height
            separator(policy, selected.continuation, page, top, target)
            var cursor = top + policy.height
            for (slice in selected.slices) for (index in slice.pending.offset until slice.pending.offset + slice.count) {
                val line = slice.pending.measured.lines[index]
                FlowLayouter.placeLine(line.elements, page.margins.left, cursor + line.before, target)
                cursor += line.height
            }
        }
        pending = selected?.remaining.orEmpty()
        plan = null
        requiredHere = emptySet()
    }

    private fun checkWidth(policy: PdfNoteSeparator) {
        if (policy.width > width + .001f || (policy.continuationWidth ?: width) > width + .001f) unsupported("Note separator leaves the text area")
    }

    companion object {
        fun separator(policy: PdfNoteSeparator, continuation: Boolean, page: PdfPage, top: Float, target: MutableList<PositionedElement>) {
            val available = page.width - page.margins.left - page.margins.right
            val width = if (continuation) policy.continuationWidth ?: available else policy.width
            if (width > available + .001f) unsupported("Note separator leaves the text area")
            target += PositionedRectangle(page.margins.left, top + policy.offset, width, policy.stroke.width, policy.stroke.color, null)
        }
        private fun overflow(): Nothing = throw PdfRenderException(PdfFailureCode.CONTENT_OVERFLOW, "A protected note fragment does not fit")
        private fun limit(): Nothing = throw PdfRenderException(PdfFailureCode.RESOURCE_LIMIT, "Note line budget exceeded")
        private fun unsupported(message: String): Nothing = throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, message)
    }
}
