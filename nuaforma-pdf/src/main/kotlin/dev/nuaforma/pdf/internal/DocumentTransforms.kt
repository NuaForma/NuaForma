package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.*

/** Pure immutable reconstruction; traversal order and identities do not depend on physical pages. */
internal object DocumentTransforms {
    enum class Scope { BODY, CELL, FRAME, BAND }

    fun map(document: PdfDocument, normalize: Boolean = false,
        paragraph: (PdfParagraph, String, Scope) -> List<PdfParagraph>,
    ): PdfDocument {
        var textSize = 0L
        fun metadata(block: PdfBlock, path: String, role: PdfSemanticRole) = block.semantics ?: if (normalize) PdfBlockSemantics(path, role) else null
        fun image(value: PdfImage, path: String): PdfImage {
            if (!normalize || value.semantics != null) return value
            return PdfImage(value.bytes(), value.width, value.height, value.crop, PdfBlockSemantics(path, PdfSemanticRole.FIGURE))
        }
        fun blocks(values: List<PdfBlock>, path: String, scope: Scope): List<PdfBlock> = values.flatMapIndexed { index, block ->
            val key = semanticChildId(path, "b$index")
            when (block) {
                is PdfParagraph -> {
                    val role = when {
                        block.contents != null -> PdfSemanticRole.CONTENTS
                        block.format.outlineLevel != null -> PdfSemanticRole.HEADING
                        else -> PdfSemanticRole.PARAGRAPH
                    }
                    val own = metadata(block, key, role)?.let { if (it.role == PdfSemanticRole.HEADING && it.level == null) it.copy(level = block.format.outlineLevel ?: 0) else it }
                    val input = if (normalize) PdfParagraph(block.runs.mapIndexed { runIndex, run ->
                        if (run is PdfImageRun) PdfImageRun(image(run.image, semanticChildId(key, "image$runIndex"))) else run
                    }, block.spaceBefore, block.spaceAfter, block.format, block.listLabel,
                        block.anchoredImages.mapIndexed { anchorIndex, anchor -> PdfAnchoredImage(image(anchor.image, semanticChildId(key, "anchor$anchorIndex")),
                            anchor.left, anchor.top, anchor.anchor, anchor.wrap, anchor.distances) }, own, block.contents) else block
                    if (input.contents != null && scope != Scope.BODY) unsupported("Contents require a body block")
                    paragraph(input, key, scope).also { output ->
                        textSize += output.sumOf { p -> p.runs.sumOf { when (it) { is PdfTextRun -> it.text.length.toLong(); is PdfPageField -> 4L; else -> 0L } } + (p.listLabel?.value?.text?.length ?: 0) }
                        if (textSize > 100_000) throw PdfRenderException(PdfFailureCode.RESOURCE_LIMIT, "Expanded document text budget exceeded")
                    }
                }
                is PdfImage -> listOf(image(block, key))
                is PdfTextBox -> listOf(PdfTextBox(block.left, block.top, block.width, block.height,
                    blocks(block.paragraphs, key, Scope.FRAME).map { it as PdfParagraph }, block.anchor, block.rotation,
                    block.padding, block.fill, block.border, block.verticalAlignment, block.wrap, metadata(block, key, PdfSemanticRole.FRAME)))
                is PdfTable -> listOf(PdfTable(block.columnWidths, block.rows.mapIndexed { rowIndex, row ->
                    PdfTableRow(row.cells.mapIndexed { cellIndex, cell -> PdfTableCell(
                        blocks(cell.blocks, semanticChildId(key, "r$rowIndex-c$cellIndex"), Scope.CELL), cell.background,
                        cell.columnSpan, cell.rowSpan, cell.verticalAlignment, cell.borders)
                    }, row.allowSplit, row.splitOversized)
                }, block.cellMargins, block.border, block.repeatedHeaderRows, block.leftIndent, block.widthPolicy, metadata(block, key, PdfSemanticRole.TABLE)))
            }
        }
        fun band(value: PdfPageBand?, path: String): PdfPageBand? = value?.let {
            PdfPageBand(it.offset, it.maxHeight, blocks(it.blocks, path, Scope.BAND))
        }
        fun pair(value: PdfPageBands?, path: String): PdfPageBands? = value?.let {
            PdfPageBands(band(it.header, semanticChildId(path, "header")), band(it.footer, semanticChildId(path, "footer")))
        }
        return PdfDocument(document.pageSequences.mapIndexed { index, sequence ->
            val key = "sequence$index"
            val flow = sequence.flow?.let { PdfFlow(band(it.header, "$key-header"), band(it.footer, "$key-footer"), it.maxPages,
                pair(it.firstPageBands, "$key-first"), pair(it.evenPageBands, "$key-even")) }
            PdfPageSequence(sequence.page, blocks(sequence.blocks, key, Scope.BODY), flow, sequence.numbering)
        }, document.maxPages, document.referenceTargets)
    }

    fun normalized(document: PdfDocument): PdfDocument = map(document, true) { p, _, _ -> listOf(p) }

    fun bodyParagraphs(document: PdfDocument): List<PdfParagraph> {
        fun collect(block: PdfBlock): Sequence<PdfParagraph> = when (block) {
            is PdfParagraph -> sequenceOf(block)
            is PdfTextBox -> block.paragraphs.asSequence()
            is PdfTable -> block.rows.asSequence().flatMap { it.cells.asSequence() }.flatMap { it.blocks.asSequence() }.flatMap { collect(it) }
            is PdfImage -> emptySequence()
        }
        return document.pageSequences.flatMap { it.blocks.flatMap { block -> collect(block).toList() } }
    }

    private fun unsupported(message: String): Nothing = throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, message)
}
