package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.*

/** Exact-line experimental flow with bounded table spans, nesting and row fragmentation. */
internal object FlowLayouter {
    internal data class MeasuredParagraph(val paragraph: PdfParagraph, val lines: List<List<PositionedElement>>, val lineHeight: Float, val heights: List<Float> = List(lines.size) { lineHeight }) {
        private val offsets = heights.runningFold(0f) { sum, value -> sum + value }

        fun height(from: Int = 0, until: Int = lines.size): Float = offsets[until] - offsets[from]

    }

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
        val floating = mutableMapOf<Int, MutableList<ImageFlow.Exclusion>>()

        fun measuredAt(index: Int) = paragraphCache.getOrPut(index) {
            val block = document.blocks[index] as PdfParagraph
            ParagraphBounds.check(block, page)
            paragraph(block, width, measurer, previous = document.blocks.getOrNull(index - 1) as? PdfParagraph, next = document.blocks.getOrNull(index + 1) as? PdfParagraph)
        }

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
                    if (block.anchoredImages.isNotEmpty() || floating[pages.lastIndex].orEmpty().any { top < it.bottom - .001f }) {
                        if (exclusions[pages.lastIndex].orEmpty().isNotEmpty()) fail(PdfFailureCode.UNSUPPORTED_TEXT, "Combining image and text-frame wrapping is not qualified")
                        val result = ImageFlow.paragraph(block, page, pages, top, after, floating, measurer, minOf(policy.maxPages, pageBudget))
                        top = result
                        after = block.spaceAfter
                        continue
                    }
                    if (exclusions[pages.lastIndex].orEmpty().isNotEmpty() && (format.keepWithNext || format.keepLines || format.widowControl)) {
                        fail(PdfFailureCode.UNSUPPORTED_TEXT, "Paragraph keep rules with wrapped frames are not qualified")
                    }
                    val measured = measuredAt(blockIndex)
                    var before = ParagraphSpacing.before(block, after, top <= page.margins.top + .001f, format.pageBreakBefore)
                    if (format.keepWithNext && blockIndex >= protectedThrough) {
                        val group = ParagraphKeeps.measure(document.blocks, blockIndex, ::measuredAt, measurer, width)
                        if (group != null) {
                            if (group.minimum > freshHeight() + .001f) overflow()
                            if (!fits(before + group.minimum)) {
                                nextPage(); before = 0f
                            }
                            protectedThrough = group.lastIndex
                        }
                    }
                    if (format.keepLines) {
                        val contentHeight = measured.height()
                        if (contentHeight > freshHeight() + .001f) overflow()
                        if (!fits(before + contentHeight)) { nextPage(); before = 0f }
                    }
                    if (!fits(before + measured.heights.first())) nextPage() else top += before
                    if (!format.widowControl || format.keepLines) {
                        for ((lineIndex, line) in measured.lines.withIndex()) {
                            val lineHeight = measured.heights[lineIndex]
                            if (lineHeight > freshHeight()) overflow()
                            avoid(lineHeight)
                            placeLine(line, page.margins.left, top, pages.last())
                            top += lineHeight
                        }
                    } else {
                        var consumed = 0
                        while (consumed < measured.lines.size) {
                            PageLayouter.checkCancellation()
                            val left = measured.lines.size - consumed
                            var count = 0
                            while (count < left && measured.height(consumed, consumed + count + 1) <= bottom - top + .001f) count++
                            if (count < left && left - count == 1) count--
                            if (count < minOf(2, left)) {
                                if (top <= page.margins.top + .001f) overflow()
                                nextPage(); continue
                            }
                            repeat(count) {
                                placeLine(measured.lines[consumed], page.margins.left, top, pages.last())
                                top += measured.heights[consumed++]
                            }
                            if (consumed < measured.lines.size) nextPage()
                        }
                    }
                    top += block.spaceAfter
                    after = block.spaceAfter
                }
                is PdfImage -> {
                    if (floating[pages.lastIndex].orEmpty().any { top < it.bottom }) fail(PdfFailureCode.UNSUPPORTED_TEXT, "Inline block images beside floating images are not qualified")
                    if (block.width > width || block.height > freshHeight()) overflow()
                    avoid(block.height)
                    if (!fits(block.height)) nextPage()
                    pages.last() += PositionedImage(block, page.margins.left, top)
                    top += block.height
                    after = 0f
                }
                is PdfTable -> {
                    if (floating[pages.lastIndex].orEmpty().any { top < it.bottom }) fail(PdfFailureCode.UNSUPPORTED_TEXT, "Tables beside floating images are not qualified")
                    if (exclusions.isNotEmpty()) fail(PdfFailureCode.UNSUPPORTED_TEXT, "Combining wrapped frames with body tables is not qualified")
                    if (block.widthPolicy == PdfTableWidthPolicy.FIXED) TableLayouter.checkWidth(block, width, page.margins.left)
                    val measured = TableLayouter.measure(block, measurer, width)
                    if (block.widthPolicy != PdfTableWidthPolicy.FIXED) TableLayouter.checkWidth(measured.table, width, page.margins.left)
                    val headerHeight = measured.offsets[block.repeatedHeaderRows]
                    val freshData = freshHeight() - headerHeight
                    if (freshData <= measured.frame + measured.closing) overflow()
                    if (block.rows.drop(block.repeatedHeaderRows).any { (it.minimumHeight ?: 0f) + measured.closing > freshData + .001f }) overflow()
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
                        val minimumOwnsHeight = row.minimumHeight?.let { it >= height - .001f } == true
                        if ((!row.allowSplit || minimumOwnsHeight) && height + measured.closing <= freshData) { nextPage(); headers(); continue }
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
        return pages.map { PageLayout(page, ParagraphLines.connect(it)) }
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

    internal fun paragraph(p: PdfParagraph, width: Float, measurer: TextMeasurer, previous: PdfParagraph? = null, next: PdfParagraph? = null, owner: PdfParagraph = p, lineArea: ((Int) -> Pair<Float, Float>)? = null): MeasuredParagraph {
        if (p.anchoredImages.isNotEmpty()) fail(PdfFailureCode.UNSUPPORTED_TEXT, "Anchored images are supported only in body paragraphs")
        if (p.format.automaticLineSpacing == null && p.minimumLineHeight == null && p.format.exactLineHeight == null)
            fail(PdfFailureCode.UNSUPPORTED_TEXT, "Paginated proof requires an explicit line spacing policy")
        val normalized = p.copy(spaceBefore = 0f, spaceAfter = 0f, borders = null, lines = emptyList())
        val local = PageLayouter.layout(PdfDocument(PdfPage(width, 14_400f, PdfMargins(0f, 0f, 0f, 0f)), listOf(normalized)), measurer, lineArea, measurement = true)
        val heights = local.lineBoxes.map { it.height }
        val height = heights.firstOrNull() ?: p.format.exactLineHeight ?: PageLayouter.automaticBox(p, measurer)?.first
            ?: fail(PdfFailureCode.UNSUPPORTED_TEXT, "Paragraph line metrics are unavailable")
        val uniform = heights.all { it == height }
        val lines = local.lineBoxes.mapIndexed { index, box ->
            val origin = if (uniform) index * height else box.top
            local.elements.subList(box.from, box.until).map { translate(it, 0f, -origin) }
        }
        return ParagraphLines.decorate(p, lines, height, width, previous, next, owner, heights)
    }

    private fun band(band: PdfPageBand, footer: Boolean, page: PdfPage, number: Int, totalPages: Int, sequencePages: Int, measurer: TextMeasurer, target: MutableList<PositionedElement>) {
        val margin = if (footer) page.margins.bottom else page.margins.top
        if (!band.reserveContentSpace && band.offset + band.maxHeight > margin + 0.001f) overflow()
        val content = bandContent(band, page, number, totalPages, sequencePages, measurer, footer)
        val (elements, height) = content
        val required = if (band.reserveContentSpace) band.offset + height else PageBandObjects.occupied(band, footer, page, content)
        if (required > margin + .001f) overflow()
        val offset = if (footer) page.height - band.offset - height else band.offset
        target += ParagraphLines.connect(elements).map { translate(it, 0f, offset) }
    }

    internal fun bandContent(band: PdfPageBand, page: PdfPage, number: Int, totalPages: Int, sequencePages: Int, measurer: TextMeasurer, footer: Boolean = false): Pair<List<PositionedElement>, Float> {
        val width = page.width - page.margins.left - page.margins.right
        val elements = mutableListOf<PositionedElement>()
        var top = 0f
        var after = 0f

        fun resolve(p: PdfParagraph) = p.copy(runs = p.runs.map { when (it) {
            is PdfPageNumber -> PdfTextRun(number.toString(), it.style)
            is PdfPageCount -> PdfTextRun(totalPages.toString(), it.style)
            is PdfSequencePageCount -> PdfTextRun(sequencePages.toString(), it.style)
            else -> it
        } })

        fun resolveTable(t: PdfTable): PdfTable = PdfTable(t.columnWidths, t.rows.map { row -> PdfTableRow(row.cells.map { cell ->
            PdfTableCell(cell.blocks.map { when (it) { is PdfParagraph -> resolve(it); is PdfTable -> resolveTable(it); is PdfImage -> it; is PdfTextBox -> error("Frame in table") } }, cell.background, cell.columnSpan, cell.rowSpan, cell.verticalAlignment, cell.borders, cell.preferredWidth)
        }, row.allowSplit, row.splitOversized, row.minimumHeight) }, t.cellMargins, t.border, t.repeatedHeaderRows, t.leftIndent, t.widthPolicy, t.semantics, t.preferredWidth)

        val floatingImages = mutableListOf<Pair<PdfAnchoredImage, Float>>()
        val floatingBoxes = mutableListOf<Pair<PdfTextBox, Float>>()
        for ((blockIndex, block) in band.blocks.withIndex()) {
            PageLayouter.checkCancellation()
            when (block) {
                is PdfTextBox -> {
                    val resolved = PdfTextBox(block.left, block.top, block.width, block.height, block.paragraphs.map(::resolve),
                        block.anchor, block.rotation, block.padding, block.fill, block.border, block.verticalAlignment, block.wrap, block.semantics)
                    floatingBoxes += resolved to top
                }
                is PdfParagraph -> {
                    if (block.format.pageBreakBefore) fail(PdfFailureCode.UNSUPPORTED_TEXT, "Page breaks inside page bands are unsupported")
                    ParagraphBounds.check(block, page)
                    val p = paragraph(resolve(block).copy(anchoredImages = emptyList()), width, measurer, previous = band.blocks.getOrNull(blockIndex - 1) as? PdfParagraph, next = band.blocks.getOrNull(blockIndex + 1) as? PdfParagraph, owner = block)
                    top += ParagraphSpacing.before(block, after, blockIndex == 0)
                    floatingImages += block.anchoredImages.map { it to top }
                    for ((index, line) in p.lines.withIndex()) { placeLine(line, page.margins.left, top, elements); top += p.heights[index] }
                    top += block.spaceAfter
                    after = block.spaceAfter
                }
                is PdfTable -> {
                    if (block.widthPolicy == PdfTableWidthPolicy.FIXED) TableLayouter.checkWidth(block, width, page.margins.left)
                    val measured = TableLayouter.measure(resolveTable(block), measurer, width)
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
        val origin = if (footer) page.height - band.offset - top else band.offset
        elements += PageBandObjects.place(floatingImages, floatingBoxes, origin, page, measurer)
        if (!band.reserveContentSpace && PageBandObjects.occupied(band, footer, page, elements to top) > band.offset + band.maxHeight + .001f) overflow()
        return elements to top
    }

    internal fun placeLine(line: List<PositionedElement>, x: Float, top: Float, target: MutableList<PositionedElement>) {
        target += line.map { translate(it, x, top) }
    }

    internal fun translate(element: PositionedElement, x: Float, y: Float): PositionedElement = when (element) {
        is PositionedVector -> element.copy(x = element.x + x, top = element.top + y)
        is PositionedStroke -> element.copy(x = element.x + if (element.pageX) 0f else x, endX = element.endX + if (element.pageX) 0f else x,
            top = element.top + if (element.pageY) 0f else y, endY = element.endY + if (element.pageY) 0f else y)
        is PositionedText -> element.copy(x = element.x + x, baseline = element.baseline + y)
        is PositionedImage -> element.copy(x = element.x + x, top = element.top + if (element.pageY) 0f else y)
        is PositionedRectangle -> element.copy(x = element.x + x, top = element.top + y)
        is PositionedTableBorder -> element.copy(x = element.x + x, top = element.top + y)
        is PositionedBox -> element.copy(x = element.x + x, top = element.top + if (element.pageY) 0f else y)
    }

    private fun overflow(): Nothing = fail(PdfFailureCode.CONTENT_OVERFLOW, "Content exceeds the paginated proof's page, row or band area")

    private fun fail(code: PdfFailureCode, message: String): Nothing = throw PdfRenderException(code, message)

}
