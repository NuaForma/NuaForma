package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.*

/** Builds logical ownership from the model, never from coordinates or font-size heuristics. */
internal object LogicalStructureBuilder {
    fun build(document: PdfDocument, containers: Map<String, PdfBlockSemantics>): PdfLogicalStructure {
        val nodes = linkedMapOf<String, PdfSemanticNode>()
        val aliases = mutableMapOf<String, String>()
        val pendingTargets = mutableMapOf<String, String>()
        val captions = mutableMapOf<String, String>()
        fun add(node: PdfSemanticNode) {
            if (nodes.size >= 12_000) throw PdfRenderException(PdfFailureCode.RESOURCE_LIMIT, "Logical node budget exceeded")
            require(node.id !in nodes) { "Duplicate logical identity" }
            nodes[node.id] = node
        }
        fun span(id: String, role: PdfSemanticRole, parent: String, text: String, target: String? = null) {
            val previous = nodes[id]
            if (previous == null) add(PdfSemanticNode(id, role, parent, text = text, referenceName = target)) else {
                require(previous.role == role && previous.parentId == parent && previous.referenceName == target) { "Ambiguous logical span" }
                nodes[id] = previous.copy(text = previous.text.orEmpty() + text)
            }
            target?.let { pendingTargets[id] = it }
        }
        fun image(image: PdfImage, parent: String) {
            val semantic = requireNotNull(image.semantics)
            require(semantic.role == PdfSemanticRole.FIGURE) { "Invalid figure role" }
            add(PdfSemanticNode(semantic.id, PdfSemanticRole.FIGURE, semantic.parentId ?: parent))
        }
        fun paragraph(p: PdfParagraph, fallbackParent: String) {
            val semantic = requireNotNull(p.semantics)
            val parent = semantic.parentId ?: fallbackParent
            if (parent in containers && parent !in nodes) {
                val container = containers.getValue(parent)
                add(PdfSemanticNode(container.id, PdfSemanticRole.CONTENTS, container.parentId ?: fallbackParent))
            }
            val text = paragraphText(p)
            add(PdfSemanticNode(semantic.id, semantic.role, parent, semantic.level, text = text))
            semantic.captionTargetId?.let { captions[semantic.id] = it }
            p.listLabel?.let { span(semanticChildId(semantic.id, "label"), PdfSemanticRole.LABEL, semantic.id, it.value.text) }
            for ((index, run) in p.runs.withIndex()) when (run) {
                is PdfTextRun -> {
                    val contentOwner = run.semantics?.id ?: semantic.id
                    run.navigation?.destinations?.forEach { aliases[it.name] = contentOwner }
                    run.semantics?.let { span(it.id, it.role, semantic.id, run.text, it.target) }
                    val navigation = run.navigation
                    if (navigation?.link != null) {
                        val id = navigation.logicalId ?: semanticChildId(contentOwner, "link$index")
                        val named = (navigation.link as? PdfLink.Internal)?.destination
                        val external = (navigation.link as? PdfLink.External)?.uri
                        span(id, PdfSemanticRole.LINK, contentOwner, run.text, named ?: external)
                        if (external != null) pendingTargets.remove(id)
                    }
                }
                is PdfImageRun -> image(run.image, semantic.id)
                is PdfTab -> {
                    val before = p.runs.getOrNull(index - 1) as? PdfTextRun
                    val after = p.runs.getOrNull(index + 1) as? PdfTextRun
                    if (before?.semantics?.id != null && before.semantics.id == after?.semantics?.id) {
                        val id = before.semantics.id
                        nodes[id] = nodes.getValue(id).let { it.copy(text = it.text.orEmpty() + "\t") }
                        before.navigation?.logicalId?.takeIf { it in nodes }?.let { link -> nodes[link] = nodes.getValue(link).let { it.copy(text = it.text.orEmpty() + "\t") } }
                    }
                }
                else -> Unit // Existing page-band fields retain their per-physical-page meaning.
            }
            p.anchoredImages.forEach { image(it.image, semantic.id) }
        }
        fun blocks(values: List<PdfBlock>, root: String, sections: Boolean) {
            val headings = mutableListOf<Pair<Int, String>>()
            for (block in values) {
                var parent = headings.lastOrNull()?.second ?: root
                when (block) {
                    is PdfParagraph -> {
                        val semantic = requireNotNull(block.semantics)
                        if (sections && semantic.role == PdfSemanticRole.HEADING && semantic.parentId == null) {
                            val level = semantic.level ?: 0
                            while (headings.lastOrNull()?.first?.let { it >= level } == true) headings.removeAt(headings.lastIndex)
                            parent = headings.lastOrNull()?.second ?: root
                            val section = semanticChildId(semantic.id, "section")
                            add(PdfSemanticNode(section, PdfSemanticRole.SECTION, parent, level))
                            headings += level to section
                            parent = section
                        }
                        paragraph(block, parent)
                    }
                    is PdfImage -> image(block, parent)
                    is PdfTextBox -> {
                        val semantic = requireNotNull(block.semantics)
                        require(semantic.role == PdfSemanticRole.FRAME) { "Invalid frame role" }
                        add(PdfSemanticNode(semantic.id, PdfSemanticRole.FRAME, semantic.parentId ?: parent))
                        blocks(block.paragraphs, semantic.id, false)
                    }
                    is PdfTable -> {
                        val semantic = requireNotNull(block.semantics)
                        require(semantic.role == PdfSemanticRole.TABLE) { "Invalid table role" }
                        add(PdfSemanticNode(semantic.id, PdfSemanticRole.TABLE, semantic.parentId ?: parent))
                        block.rows.forEachIndexed { rowIndex, row ->
                            val rowId = semanticChildId(semantic.id, "row$rowIndex")
                            add(PdfSemanticNode(rowId, PdfSemanticRole.TABLE_ROW, semantic.id))
                            row.cells.forEachIndexed { cellIndex, cell ->
                                val cellId = semanticChildId(rowId, "cell$cellIndex")
                                add(PdfSemanticNode(cellId, if (rowIndex < block.repeatedHeaderRows) PdfSemanticRole.TABLE_HEADER_CELL else PdfSemanticRole.TABLE_CELL, rowId))
                                blocks(cell.blocks, cellId, false)
                            }
                        }
                    }
                }
            }
        }
        fun band(value: PdfPageBand?, id: String, parent: String, role: PdfSemanticRole) {
            value ?: return
            add(PdfSemanticNode(id, role, parent))
            blocks(value.blocks, id, false)
        }
        add(PdfSemanticNode("_document", PdfSemanticRole.DOCUMENT, null))
        for ((index, sequence) in document.pageSequences.withIndex()) {
            val id = "sequence$index"
            add(PdfSemanticNode(id, PdfSemanticRole.SECTION, "_document"))
            blocks(sequence.blocks, id, true)
            sequence.flow?.let { flow ->
                band(flow.header, "$id-header", id, PdfSemanticRole.HEADER); band(flow.footer, "$id-footer", id, PdfSemanticRole.FOOTER)
                band(flow.firstPageBands?.header, "$id-first-header", id, PdfSemanticRole.HEADER); band(flow.firstPageBands?.footer, "$id-first-footer", id, PdfSemanticRole.FOOTER)
                band(flow.evenPageBands?.header, "$id-even-header", id, PdfSemanticRole.HEADER); band(flow.evenPageBands?.footer, "$id-even-footer", id, PdfSemanticRole.FOOTER)
            }
        }
        // A multi-run bookmark describes the containing range, not only its first numbered label.
        for (target in document.referenceTargets) if (target.runs.size > 1) aliases[target.name]?.let { first ->
            val node = nodes.getValue(first)
            if (node.role in setOf(PdfSemanticRole.TEXT, PdfSemanticRole.LABEL, PdfSemanticRole.REFERENCE)) node.parentId?.let { aliases[target.name] = it }
        }
        for ((id, name) in pendingTargets) nodes[id] = nodes.getValue(id).copy(targetId = name.takeIf { it in nodes } ?: aliases[name])
        for ((id, target) in captions) {
            require(nodes[target]?.role in setOf(PdfSemanticRole.FIGURE, PdfSemanticRole.TABLE)) { "Missing or invalid caption association" }
            nodes[id] = nodes.getValue(id).copy(targetId = target)
        }
        return PdfLogicalStructure(nodes.values.toList())
    }

    fun paragraphText(p: PdfParagraph): String? {
        if (p.runs.any { it is PdfPageField }) return null
        val label = p.listLabel?.let { it.value.text + if (it.suffix == PdfListSuffix.NOTHING) "" else if (it.suffix == PdfListSuffix.TAB) "\t" else " " }.orEmpty()
        return label + p.runs.joinToString("") { when (it) { is PdfTextRun -> it.text; is PdfTab -> "\t"; is PdfImageRun -> "\uFFFC"; else -> "" } }
    }
}
