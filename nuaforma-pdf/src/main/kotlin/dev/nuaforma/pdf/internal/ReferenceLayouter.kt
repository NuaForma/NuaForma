package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.*

/** Bounded fixed-point pagination. All values, counters, identities and trial layouts belong to one job. */
internal object ReferenceLayouter {
    fun active(document: PdfDocument): Boolean {
        if (document.referenceTargets.isNotEmpty()) return true
        fun block(value: PdfBlock): Boolean = value.semantics != null || when (value) {
            is PdfParagraph -> value.contents != null || value.runs.any { when (it) {
                is PdfReferenceField, is PdfSequenceField -> true
                is PdfTextRun -> it.semantics != null || it.navigation?.logicalId != null
                is PdfImageRun -> it.image.semantics != null
                else -> false
            } } || value.anchoredImages.any { it.image.semantics != null }
            is PdfImage -> false
            is PdfTextBox -> value.paragraphs.any { block(it) }
            is PdfTable -> value.rows.any { row -> row.cells.any { cell -> cell.blocks.any { block(it) } } }
        }
        return document.pageSequences.any { sequence -> (sequence.blocks + sequence.flow?.bands.orEmpty().flatMap { it.blocks }).any { block(it) } }
    }

    fun layout(document: PdfDocument, measurer: TextMeasurer, control: PdfRenderControl): DocumentLayout {
        val normalized = DocumentTransforms.map(document, true) { p, _, scope ->
            if (scope in setOf(DocumentTransforms.Scope.BAND, DocumentTransforms.Scope.FRAME) && p.runs.any { it is PdfSequenceField })
                unsupported("Sequence fields require main document content")
            if (scope == DocumentTransforms.Scope.BAND && p.runs.filterIsInstance<PdfReferenceField>().any { it.kind == PdfReferenceKind.CURRENT_PAGE })
                unsupported("Use the per-page field type in page bands")
            listOf(p)
        }
        val evaluator = ReferenceValues(normalized)
        val fields = normalized.allParagraphs().flatMap { it.runs.filterIsInstance<PdfReferenceField>() }
        val containers = normalized.allParagraphs().filter { it.contents != null }.associate { p -> requireNotNull(p.semantics).let { it.id to it } }
        var positions = emptyMap<String, Int>()
        var pageCount = 1
        var sequenceCounts = emptyMap<String, Int>()
        val snapshots = mutableListOf<List<String>>()
        val seen = mutableSetOf<Map<String, String>>()
        for (iteration in 1..8) {
            control.checkActive()
            val values = evaluator.evaluate(positions, pageCount, sequenceCounts)
            val prepared = prepare(normalized, fields, values, positions)
            prepared.document.validateNavigation()
            val structure = LogicalStructureBuilder.build(prepared.document, containers)
            val ids = structure.nodes.map { it.id }
            if (snapshots.isNotEmpty() && snapshots.first() != ids) unsupported("Logical identities changed during field resolution")
            snapshots += ids
            val placed = DocumentLayouter.plain(prepared.document, measurer, control)
            DocumentLayouter.validatePlacedLinks(placed)
            val actual = DocumentLayouter.placedTargets(placed)
            for (field in fields) when (field.kind) {
                PdfReferenceKind.PAGE -> if (field.target !in actual) unsupported("Page reference target is not placed")
                PdfReferenceKind.CURRENT_PAGE -> {
                    if (field.id !in actual) unsupported("Current-page field is not placed")
                    val pages = placed.pages.indices.filter { index -> DocumentLayouter.texts(placed.pages[index].elements).any { it.span?.id == field.id } }
                    if (pages.size > 1) unsupported("Repeated current-page fields are not qualified")
                }
                PdfReferenceKind.SEQUENCE_PAGES -> if (field.target !in placed.sequenceCounts) unsupported("Unknown page sequence count")
                else -> Unit
            }
            val nextValues = evaluator.evaluate(actual, placed.pages.size, placed.sequenceCounts)
            val next = prepare(normalized, fields, nextValues, actual)
            if (next.values == prepared.values) return placed.copy(structure = structure, identities = snapshots.toList(), fieldValues = prepared.values)
            if (!seen.add(prepared.values)) unsupported("Field pagination contains a cycle")
            positions = actual
            pageCount = placed.pages.size
            sequenceCounts = placed.sequenceCounts
        }
        unsupported("Field pagination did not converge within eight passes")
    }

    private data class Prepared(val document: PdfDocument, val values: Map<String, String>)
    private data class Heading(val paragraph: PdfParagraph, val id: String, val level: Int, val title: String)

    private fun prepare(document: PdfDocument, fields: List<PdfReferenceField>, values: Map<String, String>, positions: Map<String, Int>): Prepared {
        val expanded = values.toMutableMap()
        fun navigation(original: PdfTextNavigation?, link: PdfLink?, id: String): PdfTextNavigation? {
            require(original?.link == null || link == null || original.link == link) { "Conflicting field navigation" }
            val actual = original?.link ?: link
            return if (actual == null && original?.destinations.isNullOrEmpty()) null else
                PdfTextNavigation(actual, original?.destinations.orEmpty(), original?.logicalId ?: semanticChildId(id, "link"))
        }
        val resolved = DocumentTransforms.map(document) { p, _, _ ->
            val runs = p.runs.map { run -> when (run) {
                is PdfReferenceField -> {
                    val role = if (run.kind in setOf(PdfReferenceKind.TEXT, PdfReferenceKind.PAGE)) PdfSemanticRole.REFERENCE else PdfSemanticRole.LABEL
                    val link = if (run.linked) PdfLink.Internal(requireNotNull(run.target)) else null
                    PdfTextRun(values.getValue(run.id), run.style, navigation(run.navigation, link, run.id),
                        PdfSpanSemantics(run.id, role, run.target))
                }
                is PdfSequenceField -> PdfTextRun(values.getValue(run.id), run.style, navigation(run.navigation, null, run.id), PdfSpanSemantics(run.id, PdfSemanticRole.LABEL))
                else -> run
            } }
            listOf(PdfParagraph(runs, p.spaceBefore, p.spaceAfter, p.format, p.listLabel, p.anchoredImages, p.semantics, p.contents))
        }
        val headings = DocumentTransforms.bodyParagraphs(resolved).mapNotNull { p ->
            val semantic = requireNotNull(p.semantics)
            if (semantic.role != PdfSemanticRole.HEADING) null else {
                val title = LogicalStructureBuilder.paragraphText(p).orEmpty().replace(Regex("\\s+"), " ").trim()
                if (title.isEmpty()) null else Heading(p, semantic.id, semantic.level ?: 0, title)
            }
        }
        val required = fields.filter { it.linked }.mapNotNull { it.target }.toMutableSet()
        resolved.allParagraphs().flatMap { it.runs.filterIsInstance<PdfTextRun>() }.forEach { run ->
            (run.navigation?.link as? PdfLink.Internal)?.let { required += it.destination }
        }
        val hasContents = resolved.allParagraphs().any { it.contents != null }
        if (hasContents) {
            if (headings.isEmpty()) unsupported("Contents require at least one logical heading")
            if (headings.any { it.paragraph.format.textDirection != null }) unsupported("Bidi contents are not qualified")
            if (resolved.allParagraphs().any { it.contents?.linked == true }) required += headings.map { it.id }
        }
        val byId = headings.associateBy { it.id }
        fun destination(p: PdfParagraph): PdfParagraph {
            val id = requireNotNull(p.semantics).id
            val heading = byId[id]
            val outline = heading != null && p.format.textDirection == null
            if (id !in required && !outline) return p
            if (p.runs.filterIsInstance<PdfTextRun>().any { run -> run.navigation?.destinations?.any { it.name == id } == true }) return p
            var attached = false
            val runs = p.runs.map { run ->
                if (!attached && run is PdfTextRun && run.text.isNotBlank()) {
                    attached = true
                    val old = run.navigation
                    run.copy(navigation = PdfTextNavigation(old?.link, old?.destinations.orEmpty() + PdfDestination(id,
                        if (outline) heading.title.take(256) else null, heading?.level ?: 0), old?.logicalId))
                } else run
            }
            if (!attached) unsupported("A navigation target requires visible text")
            return PdfParagraph(runs, p.spaceBefore, p.spaceAfter, p.format, p.listLabel, p.anchoredImages, p.semantics)
        }
        val output = DocumentTransforms.map(resolved) { p, path, _ ->
            val contents = p.contents
            if (contents == null) listOf(destination(p)) else {
                val parent = requireNotNull(p.semantics).id
                val selected = headings.filter { heading -> contents.levels.any { it.level == heading.level } }
                if (selected.isEmpty()) unsupported("No headings match the contents levels")
                selected.mapIndexed { index, heading ->
                    val level = contents.levels.single { it.level == heading.level }
                    val id = semanticChildId(parent, "entry-${heading.id}")
                    val reference = semanticChildId(id, "reference")
                    val page = (positions[heading.id] ?: 1).toString()
                    expanded[reference] = page
                    val nav = if (contents.linked) PdfTextNavigation(PdfLink.Internal(heading.id), logicalId = semanticChildId(reference, "link")) else null
                    val span = PdfSpanSemantics(reference, PdfSemanticRole.REFERENCE, heading.id)
                    val pagePolicy = document.pageSequences[path.substringBefore('-').removePrefix("sequence").toInt()].page
                    val tab = level.format.tabs ?: PdfTabSettings(listOf(PdfTabStop(
                        pagePolicy.width - pagePolicy.margins.left - pagePolicy.margins.right - level.format.rightIndent,
                        PdfTabAlignment.RIGHT, contents.leader)))
                    PdfParagraph(listOf(PdfTextRun(heading.title, level.style, nav, span), PdfTab(level.style.copy(underline = false)), PdfTextRun(page, level.style, nav, span)),
                        level.spaceBefore + if (index == 0) p.spaceBefore else 0f, level.spaceAfter + if (index == selected.lastIndex) p.spaceAfter else 0f,
                        level.format.copy(tabs = tab), semantics = PdfBlockSemantics(id, PdfSemanticRole.CONTENTS_ENTRY, heading.level, parent))
                }
            }
        }
        return Prepared(output, expanded.toMap())
    }

    private fun unsupported(message: String): Nothing = throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, message)
}
