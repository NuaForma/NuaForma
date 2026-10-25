package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.*

/** Exact-line experimental flow with bounded table spans, nesting and row fragmentation. */
internal object FlowLayouter {
    internal data class MeasuredParagraph(val paragraph: PdfParagraph, val lines: List<List<PositionedElement>>, val lineHeight: Float)

    fun layout(document: PdfDocument, measurer: TextMeasurer, pageBudget: Int = document.maxPages): List<PageLayout> {
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
            if (pages.size >= minOf(policy.maxPages, pageBudget)) fail(PdfFailureCode.RESOURCE_LIMIT, "Page budget exceeded")
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
                    if (block.runs.any { it is PdfPageField }) fail(PdfFailureCode.UNSUPPORTED_TEXT, "Page fields are supported only in page bands")
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
                    if (block.widthPolicy == PdfTableWidthPolicy.FIXED) TableLayouter.checkWidth(block, width, page.margins.left)
                    val measured = TableLayouter.measure(block, measurer)
                    if (block.widthPolicy != PdfTableWidthPolicy.FIXED) TableLayouter.checkWidth(measured.table, width, page.margins.left)
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
                    val tableStartPage = pages.size
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
                            TableLayouter.slice(measured, measured.offsets[start], measured.offsets[index], left, top, pages.last(), closeStart = start > block.repeatedHeaderRows, closeEnd = index < block.rows.size)
                            top += sum
                            continue
                        }
                        if (!row.allowSplit && height + measured.closing <= freshData) { nextPage(); headers(); continue }
                        if (measured.customBorders && !row.allowSplit) fail(PdfFailureCode.UNSUPPORTED_TEXT, "Oversized non-splitting custom-border rows are not qualified")
                        if (!row.allowSplit && !row.splitOversized) overflow()
                        if (measured.cells.any { it.placement.cell.rowSpan > 1 }) overflow()
                        if (!row.allowSplit && top > page.margins.top + headerHeight + 0.001f) { nextPage(); headers() }
                        val end = measured.rowCells(index).maxOf { it.contentHeight }
                        var consumed = 0f
                        while (consumed < end - 0.001f) {
                            val capacity = bottom - top - measured.frameAt(index) - measured.closing
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
                    if (measured.customBorders && !measured.consistentBorders && pages.size != tableStartPage) fail(PdfFailureCode.UNSUPPORTED_TEXT, "Conflicting custom table borders across pages are not qualified")
                    after = 0f
                }
            }
        }
        return pages.map { PageLayout(page, it.toList()) }
    }

    fun decorate(layouts: List<PageLayout>, policy: PdfFlow, firstPageNumber: Int, totalPages: Int, measurer: TextMeasurer): List<PageLayout> {
        require(totalPages in 1..32 && firstPageNumber >= 0 && firstPageNumber + layouts.size - 1 <= 9_999 && layouts.size <= totalPages)
        return layouts.mapIndexed { index, layout ->
            PageLayouter.checkCancellation()
            val page = layout.page
            val decorated = mutableListOf<PositionedElement>()
            val number = firstPageNumber + index
            val selected = when {
                index == 0 && policy.firstPageBands != null -> policy.firstPageBands
                number % 2 == 0 && policy.evenPageBands != null -> policy.evenPageBands
                else -> PdfPageBands(policy.header, policy.footer)
            }
            selected.header?.let { band(it, false, page, number, totalPages, layouts.size, measurer, decorated) }
            selected.footer?.let { band(it, true, page, number, totalPages, layouts.size, measurer, decorated) }
            decorated += layout.elements
            PageLayout(page, decorated.toList())
        }
    }

    internal fun paragraph(p: PdfParagraph, width: Float, measurer: TextMeasurer): MeasuredParagraph {
        val height = PageLayouter.automaticBox(p, measurer)?.first ?: p.format.exactLineHeight ?: fail(PdfFailureCode.UNSUPPORTED_TEXT, "Paginated proof requires explicit exact line spacing")
        val normalized = PdfParagraph(p.runs, 0f, 0f, p.format, p.listLabel)
        val local = PageLayouter.layout(PdfDocument(PdfPage(width, 14_400f, PdfMargins(0f, 0f, 0f, 0f)), listOf(normalized)), measurer)
        val lines = local.elements.groupBy { element -> when (element) {
            is PositionedText -> element.baseline
            is PositionedImage -> element.top + element.image.height
            is PositionedRectangle -> error("Unexpected paragraph rectangle")
            is PositionedTableBorder -> error("Unexpected paragraph table border")
            is PositionedBox -> error("Unexpected paragraph frame")
        } }.values.mapIndexed { index, line -> line.map { translate(it, 0f, -index * height) } }
        return MeasuredParagraph(p, lines, height)
    }

    private fun band(band: PdfPageBand, footer: Boolean, page: PdfPage, number: Int, totalPages: Int, sequencePages: Int, measurer: TextMeasurer, target: MutableList<PositionedElement>) {
        val margin = if (footer) page.margins.bottom else page.margins.top
        if (band.offset + band.maxHeight > margin + 0.001f) overflow()
        val width = page.width - page.margins.left - page.margins.right
        val elements = mutableListOf<PositionedElement>()
        var top = 0f
        var after = 0f
        fun resolve(p: PdfParagraph) = PdfParagraph(p.runs.map { when (it) {
            is PdfPageNumber -> PdfTextRun(number.toString(), it.style)
            is PdfPageCount -> PdfTextRun(totalPages.toString(), it.style)
            is PdfSequencePageCount -> PdfTextRun(sequencePages.toString(), it.style)
            else -> it
        } }, p.spaceBefore, p.spaceAfter, p.format, p.listLabel)
        fun resolveTable(t: PdfTable): PdfTable = PdfTable(t.columnWidths, t.rows.map { row -> PdfTableRow(row.cells.map { cell ->
            PdfTableCell(cell.blocks.map { when (it) { is PdfParagraph -> resolve(it); is PdfTable -> resolveTable(it); is PdfImage -> it; is PdfTextBox -> error("Frame in table") } }, cell.background, cell.columnSpan, cell.rowSpan, cell.verticalAlignment, cell.borders)
        }, row.allowSplit, row.splitOversized) }, t.cellMargins, t.border, t.repeatedHeaderRows, t.leftIndent, t.widthPolicy)
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
                    if (block.widthPolicy == PdfTableWidthPolicy.FIXED) TableLayouter.checkWidth(block, width, page.margins.left)
                    val measured = TableLayouter.measure(resolveTable(block), measurer)
                    if (block.widthPolicy != PdfTableWidthPolicy.FIXED) TableLayouter.checkWidth(measured.table, width, page.margins.left)
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
        is PositionedTableBorder -> element.copy(x = element.x + x, top = element.top + y)
        is PositionedBox -> element.copy(x = element.x + x, top = element.top + y)
    }

    private fun overflow(): Nothing = fail(PdfFailureCode.CONTENT_OVERFLOW, "Content exceeds the paginated proof's page, row or band area")
    private fun fail(code: PdfFailureCode, message: String): Nothing = throw PdfRenderException(code, message)
}
