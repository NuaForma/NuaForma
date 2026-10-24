package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.*

/** Exact-line experimental flow with bounded table spans, nesting and row fragmentation. */
internal object FlowLayouter {
    internal data class MeasuredParagraph(val paragraph: PdfParagraph, val lines: List<List<PositionedElement>>, val lineHeight: Float)

    fun layout(document: PdfDocument, measurer: TextMeasurer): List<PageLayout> {
        val policy = requireNotNull(document.flow)
        val page = document.page
        val width = page.width - page.margins.left - page.margins.right
        val bottom = page.height - page.margins.bottom
        val pages = mutableListOf<MutableList<PositionedElement>>(mutableListOf())
        var top = page.margins.top
        var after = 0f
        val exclusions = mutableMapOf<Int, MutableList<Pair<Float, Float>>>()
        val paragraphCache = mutableMapOf<Int, MeasuredParagraph>()
        fun measuredAt(index: Int) = paragraphCache.getOrPut(index) { paragraph(document.blocks[index] as PdfParagraph, width, measurer) }
        var protectedThrough = -1
        fun nextPage() {
            if (pages.size >= policy.maxPages) fail(PdfFailureCode.RESOURCE_LIMIT, "Page budget exceeded")
            pages += mutableListOf<PositionedElement>()
            top = page.margins.top
            after = 0f
        }
        fun fits(height: Float) = top + height <= bottom + 0.001f
        fun freshHeight() = bottom - page.margins.top
        fun avoid(height: Float) {
            while (true) {
                val collision = exclusions[pages.lastIndex].orEmpty().firstOrNull { (start, end) -> top < end - .001f && top + height > start + .001f }
                if (collision != null) { top = collision.second; continue }
                if (!fits(height)) { nextPage(); continue }
                return
            }
        }

        for ((blockIndex, block) in document.blocks.withIndex()) {
            PageLayouter.checkCancellation()
            when (block) {
                is PdfTextBox -> {
                    if (block.wrap == PdfBoxWrap.TOP_BOTTOM) {
                        if (block.anchor == PdfBoxAnchor.PARAGRAPH) {
                            if (block.top + block.height > freshHeight()) overflow()
                            if (!fits(block.top + block.height)) nextPage()
                        } else if (block.top < top - .001f || block.top + block.height > bottom) overflow()
                    }
                    val placed = BoxLayouter.layout(block, page, top, measurer)
                    pages.last() += placed
                    if (block.wrap == PdfBoxWrap.TOP_BOTTOM) exclusions.getOrPut(pages.lastIndex) { mutableListOf() } += placed.top to placed.top + block.height
                }
                is PdfParagraph -> {
                    if (block.runs.any { it is PdfPageNumber }) fail(PdfFailureCode.UNSUPPORTED_TEXT, "Page fields are supported only in page bands")
                    val format = block.format
                    if (format.pageBreakBefore && top > page.margins.top + .001f) nextPage()
                    if (exclusions[pages.lastIndex].orEmpty().isNotEmpty() && (format.keepWithNext || format.keepLines || format.widowControl)) {
                        fail(PdfFailureCode.UNSUPPORTED_TEXT, "Paragraph keep rules with wrapped frames are not qualified")
                    }
                    val measured = measuredAt(blockIndex)
                    var before = if (format.collapseSpacing) maxOf(0f, block.spaceBefore - after) else block.spaceBefore
                    if (format.keepWithNext && blockIndex >= protectedThrough) {
                        val group = ParagraphKeeps.measure(document.blocks, blockIndex, ::measuredAt, measurer)
                        if (group != null) {
                            if (group.minimum > freshHeight() + .001f) overflow()
                            if (!fits(before + group.minimum)) {
                                nextPage(); before = 0f
                            }
                            protectedThrough = group.lastIndex
                        }
                    }
                    if (format.keepLines) {
                        val contentHeight = measured.lines.size * measured.lineHeight
                        if (contentHeight > freshHeight() + .001f) overflow()
                        if (!fits(before + contentHeight)) { nextPage(); before = 0f }
                    }
                    if (!fits(before + measured.lineHeight)) nextPage() else top += before
                    if (!format.widowControl || format.keepLines) {
                        for (line in measured.lines) {
                            if (measured.lineHeight > freshHeight()) overflow()
                            avoid(measured.lineHeight)
                            placeLine(line, page.margins.left, top, pages.last())
                            top += measured.lineHeight
                        }
                    } else {
                        var consumed = 0
                        while (consumed < measured.lines.size) {
                            PageLayouter.checkCancellation()
                            val left = measured.lines.size - consumed
                            var count = minOf(left, kotlin.math.floor((bottom - top + .001f) / measured.lineHeight).toInt())
                            if (count < left && left - count == 1) count--
                            if (count < minOf(2, left)) {
                                if (top <= page.margins.top + .001f) overflow()
                                nextPage(); continue
                            }
                            repeat(count) {
                                placeLine(measured.lines[consumed++], page.margins.left, top, pages.last())
                                top += measured.lineHeight
                            }
                            if (consumed < measured.lines.size) nextPage()
                        }
                    }
                    top += block.spaceAfter
                    after = block.spaceAfter
                }
                is PdfImage -> {
                    if (block.width > width || block.height > freshHeight()) overflow()
                    avoid(block.height)
                    if (!fits(block.height)) nextPage()
                    pages.last() += PositionedImage(block, page.margins.left, top)
                    top += block.height
                    after = 0f
                }
                is PdfTable -> {
                    if (exclusions.isNotEmpty()) fail(PdfFailureCode.UNSUPPORTED_TEXT, "Combining wrapped frames with body tables is not qualified")
                    TableLayouter.checkWidth(block, width, page.margins.left)
                    val measured = TableLayouter.measure(block, measurer)
                    val headerHeight = measured.offsets[block.repeatedHeaderRows]
                    val freshData = freshHeight() - headerHeight
                    if (freshData <= measured.frame + measured.closing) overflow()
                    val left = page.margins.left + block.leftIndent
                    fun headers() {
                        TableLayouter.slice(measured, 0f, headerHeight, left, top, pages.last())
                        top += headerHeight
                    }
                    var index = block.repeatedHeaderRows
                    val first = measured.heights[index]
                    val firstRow = block.rows[index]
                    if (!fits(headerHeight + minOf(first, freshData - measured.closing) + measured.closing) ||
                        !firstRow.allowSplit && first > freshData && top > page.margins.top + 0.001f) nextPage()
                    headers()
                    while (index < block.rows.size) {
                        PageLayouter.checkCancellation()
                        val row = block.rows[index]
                        val height = measured.heights[index]
                        if (fits(height + measured.closing)) {
                            val start = index
                            var sum = 0f
                            while (index < block.rows.size && fits(sum + measured.heights[index] + measured.closing)) {
                                sum += measured.heights[index]
                                index++
                            }
                            TableLayouter.slice(measured, measured.offsets[start], measured.offsets[index], left, top, pages.last())
                            top += sum
                            continue
                        }
                        if (!row.allowSplit && height + measured.closing <= freshData) { nextPage(); headers(); continue }
                        if (!row.allowSplit && !row.splitOversized) overflow()
                        if (measured.cells.any { it.placement.cell.rowSpan > 1 }) overflow()
                        if (!row.allowSplit && top > page.margins.top + headerHeight + 0.001f) { nextPage(); headers() }
                        val end = measured.rowCells(index).maxOf { it.contentHeight }
                        var consumed = 0f
                        while (consumed < end - 0.001f) {
                            val capacity = bottom - top - measured.frame - measured.closing
                            val cut = TableLayouter.cut(measured, index, consumed, consumed + maxOf(0f, capacity))
                            if (cut <= consumed + 0.001f) {
                                if (top <= page.margins.top + headerHeight + 0.001f) overflow()
                                nextPage(); headers(); continue
                            }
                            top += TableLayouter.fragment(measured, index, consumed, cut, left, top, pages.last())
                            consumed = cut
                            if (consumed < end - 0.001f) { nextPage(); headers() }
                        }
                        index++
                    }
                    top += measured.closing
                    after = 0f
                }
            }
        }
        return pages.mapIndexed { index, elements ->
            val decorated = mutableListOf<PositionedElement>()
            policy.header?.let { band(it, false, page, index + 1, measurer, decorated) }
            policy.footer?.let { band(it, true, page, index + 1, measurer, decorated) }
            decorated += elements
            PageLayout(page, decorated.toList())
        }
    }

    internal fun paragraph(p: PdfParagraph, width: Float, measurer: TextMeasurer): MeasuredParagraph {
        val height = p.format.exactLineHeight ?: fail(PdfFailureCode.UNSUPPORTED_TEXT, "Paginated proof requires explicit exact line spacing")
        val normalized = PdfParagraph(p.runs, 0f, 0f, p.format, p.listLabel)
        val local = PageLayouter.layout(PdfDocument(PdfPage(width, 14_400f, PdfMargins(0f, 0f, 0f, 0f)), listOf(normalized)), measurer)
        val lines = local.elements.groupBy { element -> when (element) {
            is PositionedText -> element.baseline
            is PositionedImage -> element.top + element.image.height
            is PositionedRectangle -> error("Unexpected paragraph rectangle")
            is PositionedBox -> error("Unexpected paragraph frame")
        } }.values.mapIndexed { index, line -> line.map { translate(it, 0f, -index * height) } }
        return MeasuredParagraph(p, lines, height)
    }

    private fun band(band: PdfPageBand, footer: Boolean, page: PdfPage, number: Int, measurer: TextMeasurer, target: MutableList<PositionedElement>) {
        val margin = if (footer) page.margins.bottom else page.margins.top
        if (band.offset + band.maxHeight > margin + 0.001f) overflow()
        val width = page.width - page.margins.left - page.margins.right
        val elements = mutableListOf<PositionedElement>()
        var top = 0f
        var after = 0f
        fun resolve(p: PdfParagraph) = PdfParagraph(p.runs.map { if (it is PdfPageNumber) PdfTextRun(number.toString(), it.style) else it }, p.spaceBefore, p.spaceAfter, p.format, p.listLabel)
        fun resolveTable(t: PdfTable): PdfTable = PdfTable(t.columnWidths, t.rows.map { row -> PdfTableRow(row.cells.map { cell ->
            PdfTableCell(cell.blocks.map { when (it) { is PdfParagraph -> resolve(it); is PdfTable -> resolveTable(it); is PdfImage -> it; is PdfTextBox -> error("Frame in table") } }, cell.background, cell.columnSpan, cell.rowSpan)
        }, row.allowSplit, row.splitOversized) }, t.cellMargins, t.border, t.repeatedHeaderRows, t.leftIndent)
        for (block in band.blocks) {
            when (block) {
                is PdfTextBox -> overflow()
                is PdfParagraph -> {
                    if (block.format.pageBreakBefore) fail(PdfFailureCode.UNSUPPORTED_TEXT, "Page breaks inside page bands are unsupported")
                    val p = paragraph(resolve(block), width, measurer)
                    top += if (block.format.collapseSpacing) maxOf(0f, block.spaceBefore - after) else block.spaceBefore
                    for (line in p.lines) { placeLine(line, page.margins.left, top, elements); top += p.lineHeight }
                    top += block.spaceAfter
                    after = block.spaceAfter
                }
                is PdfTable -> {
                    TableLayouter.checkWidth(block, width, page.margins.left)
                    val measured = TableLayouter.measure(resolveTable(block), measurer)
                    TableLayouter.slice(measured, 0f, measured.contentEnd, page.margins.left + block.leftIndent, top, elements)
                    top += measured.height
                    after = 0f
                }
                is PdfImage -> {
                    if (block.width > width) overflow()
                    elements += PositionedImage(block, page.margins.left, top); top += block.height; after = 0f
                }
            }
        }
        if (top > band.maxHeight + 0.001f) overflow()
        val offset = if (footer) page.height - band.offset - top else band.offset
        target += elements.map { translate(it, 0f, offset) }
    }

    private fun placeLine(line: List<PositionedElement>, x: Float, top: Float, target: MutableList<PositionedElement>) {
        target += line.map { translate(it, x, top) }
    }

    private fun translate(element: PositionedElement, x: Float, y: Float): PositionedElement = when (element) {
        is PositionedText -> element.copy(x = element.x + x, baseline = element.baseline + y)
        is PositionedImage -> element.copy(x = element.x + x, top = element.top + y)
        is PositionedRectangle -> element.copy(x = element.x + x, top = element.top + y)
        is PositionedBox -> element.copy(x = element.x + x, top = element.top + y)
    }

    private fun overflow(): Nothing = fail(PdfFailureCode.CONTENT_OVERFLOW, "Content exceeds the paginated proof's page, row or band area")
    private fun fail(code: PdfFailureCode, message: String): Nothing = throw PdfRenderException(code, message)
}
