package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.*

internal object TableLayouter {
    data class Piece(val top: Float, val height: Float, val elements: List<PositionedElement>)
    data class Cell(val placement: TablePlacement, val pieces: List<Piece>, val contentHeight: Float, val margins: PdfMargins,
        val widowRanges: List<ClosedFloatingPointRange<Float>>)
    data class Measured(val table: PdfTable, val cells: List<Cell>, val heights: List<Float>, val offsets: List<Float>, val topInsets: List<Float>, val customBorders: Boolean, val consistentBorders: Boolean, val closing: Float, val borderGroup: Any = Any()) {
        val contentEnd: Float get() = offsets.last()
        val height: Float get() = contentEnd + closing
        val frame: Float get() = table.rows.indices.maxOf(::frameAt)

        fun frameFor(cell: Cell) = cell.margins.top + cell.margins.bottom + if (customBorders) topInsets[cell.placement.row] else closing

        fun frameAt(row: Int) = rowCells(row).maxOfOrNull(::frameFor)
            ?: (table.cellMargins.top + table.cellMargins.bottom + if (customBorders) topInsets[row] else closing)

        fun rowCells(index: Int) = cells.filter { it.placement.row == index }

    }

    fun measure(source: PdfTable, measurer: TextMeasurer, availableWidth: Float, depth: Int = 0, band: Boolean = false): Measured {
        require(depth <= 1) { "Only one nested table level is supported" }
        if (depth > 0 && source.widthPolicy == PdfTableWidthPolicy.FIT_CONTENTS) throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Natural widths in nested tables are not qualified")
        val table = TableWidthResolver.resolve(source, measurer, availableWidth)
        val customBorders = table.rows.any { row -> row.cells.any { it.borders != null } }
        val styles = table.rows.flatMap { it.cells }.flatMap { cell ->
            cell.borders?.let { listOfNotNull(it.top, it.right, it.bottom, it.left).map { it.border } } ?: listOfNotNull(table.border)
        }.toSet()
        val consistentBorders = styles.size <= 1
        val complex = depth > 0 || table.rows.any { row -> row.cells.any { cell -> cell.columnSpan != 1 || cell.rowSpan != 1 || cell.blocks.any { it is PdfTable } } }
        if (customBorders && complex && !consistentBorders && !horizontalEdgesOnly(table, depth, styles)) {
            throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Conflicting custom borders with merged or nested cells are not qualified")
        }
        val topInsets = table.rows.map { row -> maxOf(table.border?.width ?: 0f, row.cells.maxOfOrNull { it.borders?.top?.border?.width ?: 0f } ?: 0f) }
        val heights = MutableList(table.rows.size) { table.rows[it].minimumHeight ?: 0f }
        val frame = table.cellMargins.top + table.cellMargins.bottom + (table.border?.width ?: 0f)
        val cells = table.placements().map { placement ->
            PageLayouter.checkCancellation()
            val margins = placement.cell.margins ?: table.cellMargins
            val width = table.columnWidths.subList(placement.column, placement.column + placement.cell.columnSpan).sum() - margins.left - margins.right
            if (!width.isFinite() || width <= 0f) overflow("Cell margins leave no content width")
            val pieces = mutableListOf<Piece>()
            val widowRanges = mutableListOf<ClosedFloatingPointRange<Float>>()
            var top = 0f
            var after = 0f
            val blocks = placement.cell.printableBlocks
            for ((blockIndex, block) in blocks.withIndex()) {
                when (block) {
                    is PdfParagraph -> {
                        if (block.runs.any { it is PdfPageBreak })
                            throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Inline page breaks inside table cells are not qualified")
                        if (block.format.keepWithNext || block.format.pageBreakBefore)
                            throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Paragraph pagination rules inside table cells are not qualified")
                        val shapeFree = if (block.anchoredImages.isEmpty()) block else {
                            if (!band || depth != 0 || block.anchoredImages.any { !it.cellRelative }) {
                                throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Cell-relative pictures require a top-level page-band table")
                            }
                            block.copy(anchoredImages = emptyList())
                        }
                        val p = FlowLayouter.paragraph(shapeFree, width, measurer, previous = blocks.getOrNull(blockIndex - 1) as? PdfParagraph,
                            next = blocks.getOrNull(blockIndex + 1) as? PdfParagraph, owner = block, permitMarginMarker = true)
                        if (p.lines.size > 1 && block.format.keepLines)
                            throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Paragraph pagination rules inside table cells are not qualified")
                        top += ParagraphSpacing.before(block, after, blockIndex == 0)
                        val paragraphTop = top
                        for ((index, line) in p.lines.withIndex()) {
                            pieces += Piece(top, p.heights[index], line)
                            top += p.heights[index]
                        }
                        if (block.format.widowControl && p.lines.size > 1) {
                            widowRanges += paragraphTop..(paragraphTop + p.heights[0] + p.heights[1])
                            if (p.lines.size > 2) widowRanges += (top - p.heights.last() - p.heights[p.heights.lastIndex - 1])..top
                        }
                        after = ParagraphSpacing.after(block, blockIndex == blocks.lastIndex)
                        top += after
                    }
                    is PdfTable -> {
                        if (block.widthPolicy == PdfTableWidthPolicy.FIT_CONTENTS) throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Natural widths in nested tables are not qualified")
                        // A nested table's explicit indentation can consume the parent's right
                        // padding, but its border must remain inside the parent cell.
                        val child = measure(block, measurer, width, depth + 1)
                        checkWidth(child.table, width + margins.right, 0f)
                        val elements = mutableListOf<PositionedElement>()
                        slice(child, 0f, child.contentEnd, child.table.leftIndent, 0f, elements)
                        pieces += Piece(top, child.height, elements)
                        top += child.height
                        after = 0f
                    }
                    is PdfImage -> error("Cell images belong inside paragraphs")
                    is PdfTextBox -> error("Frames cannot be table cell blocks")
                }
            }
            val cellFrame = margins.top + margins.bottom + if (customBorders) topInsets[placement.row] else (table.border?.width ?: 0f)
            if (placement.cell.rowSpan == 1) heights[placement.row] = maxOf(heights[placement.row], top + cellFrame)
            Cell(placement, pieces, top, margins, widowRanges)
        }
        heights.indices.forEach { if (heights[it] == 0f) heights[it] = if (customBorders) table.cellMargins.top + table.cellMargins.bottom + topInsets[it] else frame }
        for (cell in cells.filter { it.placement.cell.rowSpan > 1 }) {
            val end = cell.placement.row + cell.placement.cell.rowSpan
            val cellFrame = cell.margins.top + cell.margins.bottom + if (customBorders) topInsets[cell.placement.row] else (table.border?.width ?: 0f)
            val deficit = cell.contentHeight + cellFrame - heights.subList(cell.placement.row, end).sum()
            if (deficit > 0f) heights[end - 1] += deficit
        }
        val offsets = mutableListOf(0f)
        heights.forEach { offsets += offsets.last() + it }
        val closing = if (customBorders) maxOf(table.border?.width ?: 0f, cells.filter { it.placement.row + it.placement.cell.rowSpan == table.rows.size }.maxOfOrNull { cell ->
            cell.placement.cell.borders?.bottom?.border?.width ?: if (cell.placement.cell.borders == null) table.border?.width ?: 0f else 0f
        } ?: 0f) else table.border?.width ?: 0f
        return Measured(table, cells, heights, offsets, topInsets, customBorders, consistentBorders, closing)
    }

    private fun horizontalEdgesOnly(table: PdfTable, depth: Int, styles: Set<PdfBorder>): Boolean =
        depth == 0 && table.widthPolicy == PdfTableWidthPolicy.FIXED && table.border == null &&
            styles.map { it.width }.distinct().size == 1 && table.rows.all { row ->
                row.cells.all { cell ->
                    val borders = cell.borders
                    cell.rowSpan == 1 && borders != null && borders.left == null && borders.right == null &&
                        cell.blocks.none { it is PdfTable }
                }
            }

    fun slice(layout: Measured, from: Float, until: Float, x: Float, top: Float, target: MutableList<PositionedElement>, closeStart: Boolean = false, closeEnd: Boolean = false) {
        val table = layout.table
        for (cell in layout.cells) {
            val start = layout.offsets[cell.placement.row]
            val end = layout.offsets[cell.placement.row + cell.placement.cell.rowSpan]
            val a = maxOf(start, from)
            val b = minOf(end, until)
            if (a >= b - 0.001f) continue
            val alignment = cell.placement.cell.verticalAlignment
            if (alignment != PdfVerticalAlignment.TOP && (start < from - 0.001f || end > until + 0.001f)) {
                throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Vertical alignment across page fragments is not qualified")
            }
            val spare = maxOf(0f, end - start - layout.frameFor(cell) - cell.contentHeight)
            val shift = verticalShift(alignment, spare)
            val left = x + table.columnWidths.take(cell.placement.column).sum()
            val width = table.columnWidths.subList(cell.placement.column, cell.placement.column + cell.placement.cell.columnSpan).sum()
            if (layout.customBorders) {
                strokeFrame(layout, cell.placement.cell,
                    PositionedRectangle(left, top + a - from, width, b - a, cell.placement.cell.background, null), top + b - from,
                    a == start || closeStart && a == from, b == end || closeEnd && b == until,
                    if (b == layout.contentEnd || closeEnd && b == until) layout.closing else 0f, target)
            } else target += PositionedRectangle(left, top + a - from + layout.closing / 2, width, b - a, cell.placement.cell.background, table.border)
            for (piece in cell.pieces) {
                val inset = if (layout.customBorders) layout.topInsets[cell.placement.row] else layout.closing
                val y = start + inset + cell.margins.top + shift + piece.top
                if (y + piece.height <= from + 0.001f || y >= until - 0.001f) continue
                if (y < from - 0.001f || y + piece.height > until + 0.001f) overflow("Merged cell content crosses an unsupported row boundary")
                target += piece.elements.map { element ->
                    clipParagraph(translate(element, left + cell.margins.left, top + y - from),
                        RectangleClip(left, top + a - from, left + width, top + b - from))
                }
            }
        }
    }

    fun cut(layout: Measured, row: Int, from: Float, limit: Float): Float {
        if (layout.customBorders && !layout.consistentBorders) throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Conflicting borders in fragmented rows are not qualified")
        var end = minOf(layout.rowCells(row).maxOf { it.contentHeight }, limit)
        var remaining = 20_000
        while (remaining-- > 0) {
            PageLayouter.checkCancellation()
            val backedUp = layout.rowCells(row).minOf { cell -> safeCutBefore(cell, end) }
            if (backedUp == end) return end
            end = backedUp
            if (end <= from + 0.001f) return from
        }
        throw PdfRenderException(PdfFailureCode.RESOURCE_LIMIT, "Row fragmentation budget exceeded")
    }

    private fun safeCutBefore(cell: Cell, cut: Float): Float {
        val betweenPieces = cell.pieces.asSequence()
            .filter { it.top < cut - .001f && it.top + it.height > cut + .001f }.minOfOrNull { it.top }
        val betweenWidows = cell.widowRanges.asSequence()
            .filter { it.start < cut - .001f && it.endInclusive > cut + .001f }.minOfOrNull { it.start }
        return minOf(betweenPieces ?: cut, betweenWidows ?: cut)
    }

    fun fragment(layout: Measured, row: Int, from: Float, until: Float, x: Float, top: Float, target: MutableList<PositionedElement>): Float {
        val table = layout.table
        val height = until - from + layout.frameAt(row)
        for (cell in layout.rowCells(row)) {
            val left = x + table.columnWidths.take(cell.placement.column).sum()
            val width = table.columnWidths.subList(cell.placement.column, cell.placement.column + cell.placement.cell.columnSpan).sum()
            if (layout.customBorders) strokeFrame(layout, cell.placement.cell,
                PositionedRectangle(left, top, width, height, cell.placement.cell.background, null), top + height,
                true, true, layout.closing, target)
            else target += PositionedRectangle(left, top + layout.closing / 2, width, height, cell.placement.cell.background, table.border)
            // Word aligns a whole cell within the first physical fragment. Split cell content remains top-aligned.
            val spare = if (from <= .001f && cell.contentHeight <= until + .001f)
                maxOf(0f, height - layout.frameFor(cell) - cell.contentHeight) else 0f
            val shift = verticalShift(cell.placement.cell.verticalAlignment, spare)
            for (piece in cell.pieces) {
                if (piece.top < from - 0.001f || piece.top + piece.height > until + 0.001f) continue
                val inset = if (layout.customBorders) layout.topInsets[row] else layout.closing
                target += piece.elements.map { element ->
                    clipParagraph(translate(element, left + cell.margins.left, top + inset + cell.margins.top + piece.top - from + shift),
                        RectangleClip(left, top, left + width, top + height))
                }
            }
        }
        return height
    }

    private fun verticalShift(alignment: PdfVerticalAlignment, spare: Float): Float = when (alignment) {
        PdfVerticalAlignment.TOP -> 0f
        PdfVerticalAlignment.CENTER -> spare / 2
        PdfVerticalAlignment.BOTTOM -> spare
    }

    private fun strokeFrame(layout: Measured, cell: PdfTableCell, frame: PositionedRectangle, bottom: Float,
                            drawTop: Boolean, drawBottom: Boolean, verticalExtra: Float, target: MutableList<PositionedElement>) {
        target += frame
        val uniform = layout.table.border?.let { PdfEdgeStroke(it) }
        val policy = cell.borders ?: PdfCellBorders(uniform, uniform, uniform, uniform)

        fun edge(stroke: PdfEdgeStroke?, x: Float, y: Float, length: Float, horizontal: Boolean) {
            if (stroke != null) target += PositionedTableBorder(x, y, length, horizontal, stroke, layout.borderGroup)
        }

        if (drawTop) edge(policy.top, frame.x, frame.top, frame.width, true)
        if (drawBottom) edge(policy.bottom, frame.x, bottom, frame.width, true)
        edge(policy.left, frame.x, frame.top, frame.height + verticalExtra, false)
        edge(policy.right, frame.x + frame.width, frame.top, frame.height + verticalExtra, false)
    }

    fun checkWidth(table: PdfTable, available: Float, left: Float, physicalRight: Float = available) {
        val right = table.leftIndent + table.columnWidths.sum()
        val limit = minOf(available + table.rightOverflow, physicalRight)
        if (left + table.leftIndent < 0 || right > limit + 0.01f) overflow("Table exceeds its containing width")
        if (table.rightOverflow > 0f) {
            val stroke = maxOf(table.border?.width ?: 0f, table.rows.maxOf { row ->
                row.cells.maxOfOrNull { it.borders?.right?.border?.width ?: 0f } ?: 0f
            }) / 2f
            if (right + stroke > physicalRight) overflow("Table exceeds its physical right boundary")
        }
    }

    private fun translate(element: PositionedElement, x: Float, y: Float): PositionedElement = when (element) {
        is PositionedDestination -> element.copy(x = element.x + x, top = element.top + y)
        is PositionedVector -> element.copy(x = element.x + x, top = element.top + y)
        is PositionedStroke -> FlowLayouter.translate(element, x, y)
        is PositionedText -> element.copy(x = element.x + x, baseline = element.baseline + y)
        is PositionedImage -> element.copy(x = element.x + x, top = element.top + y, clip = element.clip?.translated(x, y))
        is PositionedRectangle -> element.copy(x = element.x + x, top = element.top + y, clip = element.clip?.translated(x, y))
        is PositionedTableBorder -> element.copy(x = element.x + x, top = element.top + y)
        is PositionedBox -> element.copy(x = element.x + x, top = element.top + y)
    }

    private fun overflow(message: String): Nothing = throw PdfRenderException(PdfFailureCode.CONTENT_OVERFLOW, message)

    private fun clipParagraph(element: PositionedElement, bounds: RectangleClip): PositionedElement =
        if (element is PositionedRectangle && element.paragraph != null)
            element.copy(clip = element.clip?.intersect(bounds) ?: bounds) else element

}
