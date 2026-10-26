package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.*

/** Request-local, non-executable symbolic evaluation with explicit recursion and text bounds. */
internal class ReferenceValues(private val document: PdfDocument) {
    private val paragraphs = document.allParagraphs()
    private val fields = paragraphs.flatMap { it.runs }.filterIsInstance<PdfReferenceField>().associateBy { it.id }
    private val sequences = sequenceValues()
    private val definitions = linkedMapOf<String, List<PdfInline>>().apply {
        for (p in paragraphs) p.semantics?.let {
            val label = p.listLabel?.let { label -> listOf(PdfTextRun(label.value.text + if (label.suffix == PdfListSuffix.NOTHING) "" else " ", label.style)) }.orEmpty()
            put(it.id, label + p.runs)
        }
        document.referenceTargets.forEach { put(it.name, it.runs) }
    }

    init {
        val declared = paragraphs.flatMap { it.runs }.mapNotNull { when (it) {
            is PdfReferenceField -> it.id to it; is PdfSequenceField -> it.id to it; else -> null
        } }.toMap()
        for (target in document.referenceTargets) for (run in target.runs) when (run) {
            is PdfReferenceField -> require(declared[run.id] == run) { "Reference range has an unknown field" }
            is PdfSequenceField -> require(declared[run.id] == run) { "Reference range has an unknown sequence" }
            else -> Unit
        }
    }

    fun evaluate(positions: Map<String, Int>, pageCount: Int, sequenceCounts: Map<String, Int>): Map<String, String> {
        val result = sequences.toMutableMap()
        val active = mutableSetOf<String>()
        val cachedTargets = mutableMapOf<String, String>()
        fun bounded(parts: List<String>): String {
            if (parts.sumOf { it.length.toLong() } > 100_000) limit("Expanded reference text exceeds the budget")
            return parts.joinToString("")
        }
        fun field(id: String, depth: Int): String {
            result[id]?.let { return it }
            if (depth > 32 || !active.add(id)) unsupported("Circular or excessively nested references")
            val definition = fields[id] ?: unsupported("Unknown reference field")
            fun text(name: String): String = cachedTargets.getOrPut(name) {
                val runs = definitions[name] ?: unsupported("Missing text reference target")
                bounded(runs.map { when (it) {
                    is PdfTextRun -> it.text
                    is PdfReferenceField -> field(it.id, depth + 1)
                    is PdfSequenceField -> sequences[it.id] ?: unsupported("Unknown sequence value")
                    else -> unsupported("Tabs and non-text reference ranges are not qualified")
                } }).also { if (it.isBlank()) unsupported("Empty text reference ranges are not qualified") }
            }
            val value = when (definition.kind) {
                PdfReferenceKind.TEXT -> text(requireNotNull(definition.target))
                PdfReferenceKind.PAGE -> (positions[requireNotNull(definition.target)] ?: 1).toString()
                PdfReferenceKind.CURRENT_PAGE -> (positions[definition.id] ?: 1).toString()
                PdfReferenceKind.TOTAL_PAGES -> pageCount.toString()
                PdfReferenceKind.SEQUENCE_PAGES -> (sequenceCounts[requireNotNull(definition.target)] ?: 1).toString()
            }
            active.remove(id)
            result[id] = value
            return value
        }
        var expanded = 0L
        for (definition in fields.values) {
            expanded += field(definition.id, 0).length
            if (expanded > 100_000) limit("Expanded field budget exceeded")
        }
        return result
    }

    private fun sequenceValues(): Map<String, String> {
        data class Counter(val value: Int, val headings: IntArray)
        val counters = mutableMapOf<String, Counter>()
        val values = linkedMapOf<String, String>()
        val headings = IntArray(9)
        for (paragraph in DocumentTransforms.bodyParagraphs(document)) {
            val level = paragraph.semantics?.takeIf { it.role == PdfSemanticRole.HEADING }?.level ?: paragraph.format.outlineLevel
            level?.let { for (i in it..8) headings[i]++ }
            for (field in paragraph.runs.filterIsInstance<PdfSequenceField>()) {
                val previous = counters[field.sequence]
                val reset = field.restartAtHeadingLevel?.let { previous != null && previous.headings[it] != headings[it] } ?: false
                val value = when {
                    field.repeat -> previous?.value ?: unsupported("Sequence repeat requires a preceding value")
                    field.restart != null -> field.restart
                    previous == null || reset -> 1
                    else -> previous.value + 1
                }
                if (value !in 0..9_999) limit("Sequence numbering budget exceeded")
                if (!field.repeat) counters[field.sequence] = Counter(value, headings.clone())
                values[field.id] = value.toString()
            }
        }
        return values
    }

    private fun limit(message: String): Nothing = throw PdfRenderException(PdfFailureCode.RESOURCE_LIMIT, message)
    private fun unsupported(message: String): Nothing = throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, message)
}
