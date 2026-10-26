package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.*

/** Greedy note-aware body pagination. Existing measurement, table geometry and page bands are shared. */
internal object NoteFlowLayouter {
    private data class Work(val block: PdfBlock?, val startNotes: PdfNoteSeparator? = null)

    fun layout(document: PdfDocument, measurer: TextMeasurer, budget: Int, control: PdfRenderControl): List<PageLayout> {
        val page = document.page
        val width = page.width - page.margins.left - page.margins.right
        val physicalBottom = page.height - page.margins.bottom
        val notes = FootnoteFlow(document, measurer, control)
        val pages = mutableListOf<MutableList<PositionedElement>>(mutableListOf())
        var top = page.margins.top
        var after = 0f
        var bodyItems = 0
        var closed = false
        var endnotes: PdfNoteSeparator? = null
        val measured = java.util.IdentityHashMap<PdfParagraph, FlowLayouter.MeasuredParagraph>()
        fun paragraph(p: PdfParagraph) = measured.getOrPut(p) { FlowLayouter.paragraph(p, width, measurer) }
        fun closePage() { if (!closed) { notes.finish(pages.last()); closed = true } }
        fun nextPage() {
            closePage()
            if (pages.size >= minOf(requireNotNull(document.flow).maxPages, budget)) limit()
            pages += mutableListOf<PositionedElement>()
            top = page.margins.top; after = 0f; bodyItems = 0; closed = false
            endnotes?.let { FootnoteFlow.separator(it, true, page, top, pages.last()); top += it.height }
        }
        fun reserve(height: Float, calls: Set<String>, before: Float = 0f): Float {
            var gap = before
            while (!notes.accept(top + gap + height, calls)) {
                control.checkActive()
                // A previous note may need an entire continuation page before a later owning call.
                if (bodyItems == 0 && !notes.hasPending) overflow()
                nextPage(); gap = 0f
            }
            top += gap
            return gap
        }
        val ends = document.notes.filter { it.kind == PdfNoteKind.ENDNOTE }
        val work = document.blocks.map { Work(it) } + if (ends.isEmpty()) emptyList() else
            listOf(Work(null, ends.first().separator)) + ends.flatMap { note -> note.paragraphs.map { Work(it) } }
        val bodyBlocks = work.mapNotNull { it.block }
        var blockIndex = 0
        var protectedThrough = -1
        for (item in work) {
            control.checkActive()
            item.startNotes?.let { policy ->
                val first = paragraph(ends.first().paragraphs.first())
                val count = protectedStart(first)
                reserve(policy.height + first.paragraph.spaceBefore + count * first.lineHeight, emptySet())
                endnotes = policy
                FootnoteFlow.separator(policy, false, page, top, pages.last()); top += policy.height; after = 0f
            }
            val block = item.block ?: continue
            when (block) {
                is PdfParagraph -> {
                    if (block.anchoredImages.isNotEmpty()) unsupported("Image wrapping with notes is not qualified")
                    if (block.format.pageBreakBefore && bodyItems > 0) nextPage()
                    val p = paragraph(block)
                    var before = if (block.format.collapseSpacing) maxOf(0f, block.spaceBefore - after) else block.spaceBefore
                    if (block.format.keepWithNext && blockIndex >= protectedThrough) {
                        val groupBlocks = if (blockIndex < document.blocks.size) document.blocks else bodyBlocks
                        val group = ParagraphKeeps.measure(groupBlocks, blockIndex, { paragraph(groupBlocks[it] as PdfParagraph) }, measurer)
                        if (group != null) {
                            val calls = linkedSetOf<String>()
                            for (index in blockIndex..group.lastIndex) when (val member = bodyBlocks[index]) {
                                is PdfParagraph -> {
                                    val part = paragraph(member)
                                    val lines = if (index == group.lastIndex) protectedStart(part) else part.lines.size
                                    part.lines.take(lines).forEach { calls += NoteResolver.callIds(it) }
                                }
                                is PdfTable -> calls += NoteResolver.callIds(tablePrefix(member, measurer))
                                else -> Unit
                            }
                            if (group.minimum > physicalBottom - page.margins.top + .001f) overflow()
                            reserve(group.minimum, calls, before)
                            // reserve already applies the paragraph's initial gap.
                            before = 0f
                            protectedThrough = group.lastIndex
                        }
                    }
                    var consumed = 0
                    var paragraphPage = pages.lastIndex
                    var onPage = 0
                    while (consumed < p.lines.size) {
                        control.checkActive()
                        if (paragraphPage != pages.lastIndex) { paragraphPage = pages.lastIndex; onPage = 0 }
                        val left = p.lines.size - consumed
                        val count = when {
                            block.format.keepLines -> left
                            !block.format.widowControl -> 1
                            onPage == 0 && left == 3 -> 3
                            onPage == 0 || left == 2 -> minOf(2, left)
                            else -> 1
                        }
                        val lines = p.lines.subList(consumed, consumed + count)
                        val gap = if (consumed == 0) before else 0f
                        val trailing = if (consumed + count == p.lines.size) block.spaceAfter else 0f
                        if (!notes.accept(top + gap + count * p.lineHeight + trailing, lines.flatMap { NoteResolver.callIds(it) }.toSet())) {
                            if (bodyItems == 0 && !notes.hasPending) overflow()
                            nextPage(); before = 0f
                            // Recompute the protected start before any reservation is committed.
                            paragraphPage = pages.lastIndex; onPage = 0
                            continue
                        }
                        top += gap
                        repeat(count) { FlowLayouter.placeLine(p.lines[consumed++], page.margins.left, top, pages.last()); top += p.lineHeight; bodyItems++ }
                        onPage += count
                    }
                    top += block.spaceAfter; after = block.spaceAfter
                }
                is PdfImage -> {
                    if (block.width > width) overflow()
                    reserve(block.height, emptySet())
                    pages.last() += PositionedImage(block, page.margins.left, top); top += block.height; bodyItems++; after = 0f
                }
                is PdfTextBox -> {
                    if (block.wrap != PdfBoxWrap.NONE) unsupported("Wrapped frames with notes are not qualified")
                    val placed = BoxLayouter.layout(block, page, top, measurer)
                    if (document.notes.any { it.kind == PdfNoteKind.FOOTNOTE } && placed.x + block.width > page.margins.left && placed.x < page.width - page.margins.right)
                        unsupported("Frames inside the note text area are not qualified")
                    pages.last() += placed
                }
                is PdfTable -> {
                    val table = TableLayouter.measure(block, measurer)
                    TableLayouter.checkWidth(table.table, width, page.margins.left)
                    val left = page.margins.left + block.leftIndent
                    if (block.rows.any { it.allowSplit }) unsupported("Splitting table rows with notes is not qualified")
                    val complex = block.rows.any { row -> row.cells.any { it.rowSpan > 1 || it.blocks.any { value -> value is PdfTable } } }
                    if (complex) {
                        val elements = mutableListOf<PositionedElement>()
                        val height = table.offsets.last() + table.closing
                        TableLayouter.slice(table, 0f, table.offsets.last(), left, top, elements)
                        if (!notes.accept(top + height, NoteResolver.callIds(elements))) unsupported("Paginating spanning or nested tables with notes is not qualified")
                        pages.last().addAll(elements); top += height; bodyItems++; after = 0f
                    } else {
                        val headerHeight = table.offsets[block.repeatedHeaderRows]
                        val header = mutableListOf<PositionedElement>()
                        TableLayouter.slice(table, 0f, headerHeight, left, 0f, header)
                        if (NoteResolver.callIds(header).isNotEmpty()) unsupported("Owning calls in repeated table headers are not qualified")
                        var needHeader = true
                        for (index in block.repeatedHeaderRows until block.rows.size) {
                            val row = mutableListOf<PositionedElement>()
                            TableLayouter.slice(table, table.offsets[index], table.offsets[index + 1], left, 0f, row,
                                closeStart = index > block.repeatedHeaderRows, closeEnd = index < block.rows.lastIndex)
                            val height = table.heights[index]
                            val calls = NoteResolver.callIds(row)
                            while (!notes.accept(top + height + table.closing + if (needHeader) headerHeight else 0f, calls)) {
                                if (bodyItems == 0 && !notes.hasPending) overflow()
                                nextPage(); needHeader = true
                            }
                            if (needHeader) {
                                pages.last().addAll(header.map { FlowLayouter.translate(it, 0f, top) }); top += headerHeight
                                needHeader = false
                            }
                            pages.last().addAll(row.map { FlowLayouter.translate(it, 0f, top) }); top += height; bodyItems++
                        }
                        top += table.closing; after = 0f
                    }
                }
            }
            blockIndex++
        }
        endnotes = null
        closePage()
        while (notes.hasPending) { nextPage(); closePage() }
        return pages.map { PageLayout(page, it.toList()) }
    }

    private fun protectedStart(p: FlowLayouter.MeasuredParagraph): Int = when {
        p.paragraph.format.keepLines || p.paragraph.format.keepWithNext -> p.lines.size
        !p.paragraph.format.widowControl -> minOf(1, p.lines.size)
        p.lines.size == 3 -> 3
        else -> minOf(2, p.lines.size)
    }

    private fun tablePrefix(table: PdfTable, measurer: TextMeasurer): List<PositionedElement> {
        val measured = TableLayouter.measure(table, measurer)
        val target = mutableListOf<PositionedElement>()
        TableLayouter.slice(measured, 0f, measured.offsets[table.repeatedHeaderRows + 1], 0f, 0f, target)
        return target
    }
    private fun overflow(): Nothing = throw PdfRenderException(PdfFailureCode.CONTENT_OVERFLOW, "A protected body/note group does not fit")
    private fun limit(): Nothing = throw PdfRenderException(PdfFailureCode.RESOURCE_LIMIT, "Note document page budget exceeded")
    private fun unsupported(message: String): Nothing = throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, message)
}
