package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.*

/** Bind one logical occurrence to one definition before either field evaluation or pagination. */
internal object NoteResolver {
    fun resolve(document: PdfDocument): PdfDocument {
        val calls = document.allParagraphs().flatMap { it.runs }.filterIsInstance<PdfNoteCall>()
        if (document.notes.isEmpty() && calls.isEmpty()) {
            if (document.allParagraphs().any { p -> p.runs.any { it is PdfNoteLabel } }) unsupported("A note label requires a definition")
            return document
        }
        if (document.pageSequences.size != 1 || document.flow == null) unsupported("Notes require one paginated page sequence")
        val byId = document.notes.associateBy { it.id }
        if (calls.map { it.noteId }.toSet() != byId.keys || calls.map { it.noteId }.distinct().size != calls.size) unsupported("Notes require one owning call per definition")
        val owners = calls.associateBy { it.noteId }
        val resolved = DocumentTransforms.map(document) { p, path, scope ->
            if (scope == DocumentTransforms.Scope.NOTE && p.runs.any { it is PdfSequenceField }) unsupported("Sequences inside notes are not qualified")
            val note = if (scope == DocumentTransforms.Scope.NOTE) document.notes[path.substringBefore('-').removePrefix("note").toInt()] else null
            val runs = p.runs.map { run -> when (run) {
                is PdfNoteCall -> {
                    if (scope !in setOf(DocumentTransforms.Scope.BODY, DocumentTransforms.Scope.CELL)) unsupported("Note calls require main document text")
                    val definition = byId[run.noteId] ?: unsupported("Missing note definition")
                    PdfTextRun(definition.number.text, run.style,
                        navigation(run.navigation, definition.id, listOf(PdfDestination(run.id)), semanticChildId(run.id, "link")),
                        PdfSpanSemantics(run.id, PdfSemanticRole.NOTE_REFERENCE, definition.id))
                }
                is PdfNoteLabel -> {
                    val definition = note ?: unsupported("A note label requires its definition")
                    val call = owners.getValue(definition.id)
                    PdfTextRun(definition.number.text, run.style,
                        navigation(run.navigation, call.id, listOf(PdfDestination(definition.id), PdfDestination(definition.labelTarget)), semanticChildId(definition.id, "backlink")),
                        PdfSpanSemantics(definition.labelTarget, PdfSemanticRole.LABEL))
                }
                else -> run
            } }
            listOf(PdfParagraph(runs, p.spaceBefore, p.spaceAfter, p.format, p.listLabel, p.anchoredImages, p.semantics, p.contents))
        }
        val targets = document.notes.map { note ->
            val marker = note.paragraphs.first().runs.first() as? PdfNoteLabel ?: unsupported("A note label was already resolved")
            PdfReferenceTarget(note.labelTarget, listOf(PdfTextRun(note.number.text, marker.style)))
        }
        return PdfDocument(resolved.pageSequences, resolved.maxPages, resolved.referenceTargets + targets, resolved.notes)
    }

    fun callIds(elements: List<PositionedElement>): Set<String> = DocumentLayouter.texts(elements)
        .filter { it.span?.role == PdfSemanticRole.NOTE_REFERENCE }.map { requireNotNull(it.span).id }.toSet()

    private fun navigation(original: PdfTextNavigation?, target: String, destinations: List<PdfDestination>, id: String): PdfTextNavigation {
        val link = PdfLink.Internal(target)
        if (original?.link != null && original.link != link) unsupported("Conflicting note navigation")
        return PdfTextNavigation(link, original?.destinations.orEmpty() + destinations, original?.logicalId ?: id)
    }

    private fun unsupported(message: String): Nothing = throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, message)
}
