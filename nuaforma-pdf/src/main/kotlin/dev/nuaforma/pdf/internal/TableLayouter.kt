package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.*

internal object TableLayouter {
    data class Piece(val top: Float, val height: Float, val elements: List<PositionedElement>)
    data class Cell(val placement: TablePlacement, val pieces: List<Piece>, val contentHeight: Float)
    data class Measured(val table: PdfTable, val cells: List<Cell>, val heights: List<Float>, val offsets: List<Float>) {
        val contentEnd: Float get() = offsets.last()
        val closing: Float get() = table.border?.width ?: 0f
        val height: Float get() = contentEnd + closing
        val frame: Float get() = table.cellMargins.top + table.cellMargins.bottom + closing
        fun rowCells(index: Int) = cells.filter { it.placement.row == index }
    }

    fun measure(table: PdfTable, measurer: TextMeasurer, depth: Int = 0): Measured {
        require(depth <= 1) { "Only one nested table level is supported" }
        val heights = MutableList(table.rows.size) { 0f }
        val frame = table.cellMargins.top + table.cellMargins.bottom + (table.border?.width ?: 0f)
        val cells = table.placements().map { placement ->
            PageLayouter.checkCancellation()
            val width = table.columnWidths.subList(placement.column, placement.column + placement.cell.columnSpan).sum() - table.cellMargins.left - table.cellMargins.right
            val pieces = mutableListOf<Piece>()
            var top = 0f
            var after = 0f
            for (block in placement.cell.blocks) {
                when (block) {
                    is PdfParagraph -> {
                        if (block.format.keepWithNext || block.format.keepLines || block.format.widowControl || block.format.pageBreakBefore) {
                            throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Paragraph pagination rules inside table cells are not qualified")
                        }
                        val p = FlowLayouter.paragraph(block, width, measurer)
                        top += if (block.format.collapseSpacing) maxOf(0f, block.spaceBefore - after) else block.spaceBefore
                        for (line in p.lines) {
                            pieces += Piece(top, p.lineHeight, line)
                            top += p.lineHeight
                        }
                        top += block.spaceAfter
                        after = block.spaceAfter
                    }
                    is PdfTable -> {
                        // A nested table's explicit indentation can consume the parent's right
                        // padding, but its border must remain inside the parent cell.
                        checkWidth(block, width + table.cellMargins.right, 0f)
                        val child = measure(block, measurer, depth + 1)
                        val elements = mutableListOf<PositionedElement>()
                        slice(child, 0f, child.contentEnd, block.leftIndent, 0f, elements)
                        pieces += Piece(top, child.height, elements)
                        top += child.height
                        after = 0f
                    }
                    is PdfImage -> error("Cell images belong inside paragraphs")
                    is PdfTextBox -> error("Frames cannot be table cell blocks")
                }
            }
            if (placement.cell.rowSpan == 1) heights[placement.row] = maxOf(heights[placement.row], top + frame)
            Cell(placement, pieces, top)
        }
        heights.indices.forEach { if (heights[it] == 0f) heights[it] = frame }
        for (cell in cells.filter { it.placement.cell.rowSpan > 1 }) {
            val end = cell.placement.row + cell.placement.cell.rowSpan
            val deficit = cell.contentHeight + frame - heights.subList(cell.placement.row, end).sum()
            if (deficit > 0f) heights[end - 1] += deficit
        }
        val offsets = mutableListOf(0f)
        heights.forEach { offsets += offsets.last() + it }
        return Measured(table, cells, heights, offsets)
    }

    fun slice(layout: Measured, from: Float, until: Float, x: Float, top: Float, target: MutableList<PositionedElement>) {
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
            val spare = maxOf(0f, end - start - layout.frame - cell.contentHeight)
            val shift = when (alignment) { PdfVerticalAlignment.TOP -> 0f; PdfVerticalAlignment.CENTER -> spare / 2; PdfVerticalAlignment.BOTTOM -> spare }
            val left = x + table.columnWidths.take(cell.placement.column).sum()
            val width = table.columnWidths.subList(cell.placement.column, cell.placement.column + cell.placement.cell.columnSpan).sum()
            target += PositionedRectangle(left, top + a - from + layout.closing / 2, width, b - a, cell.placement.cell.background, table.border)
            for (piece in cell.pieces) {
                val y = start + layout.closing + table.cellMargins.top + shift + piece.top
                if (y + piece.height <= from + 0.001f || y >= until - 0.001f) continue
                if (y < from - 0.001f || y + piece.height > until + 0.001f) overflow("Merged cell content crosses an unsupported row boundary")
                target += piece.elements.map { translate(it, left + table.cellMargins.left, top + y - from) }
            }
        }
    }

    fun cut(layout: Measured, row: Int, from: Float, limit: Float): Float {
        if (layout.rowCells(row).any { it.placement.cell.verticalAlignment != PdfVerticalAlignment.TOP }) {
            throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Vertical alignment across fragmented rows is not qualified")
        }
        var end = minOf(layout.rowCells(row).maxOf { it.contentHeight }, limit)
        var remaining = 20_000
        while (remaining-- > 0) {
            val conflict = layout.rowCells(row).asSequence().flatMap { it.pieces.asSequence() }
                .filter { it.top < end - 0.001f && it.top + it.height > end + 0.001f }.minByOrNull { it.top }
            if (conflict == null) return end
            end = conflict.top
            if (end <= from + 0.001f) return from
        }
        throw PdfRenderException(PdfFailureCode.RESOURCE_LIMIT, "Row fragmentation budget exceeded")
    }

    fun fragment(layout: Measured, row: Int, from: Float, until: Float, x: Float, top: Float, target: MutableList<PositionedElement>): Float {
        val table = layout.table
        val height = until - from + layout.frame
        for (cell in layout.rowCells(row)) {
            val left = x + table.columnWidths.take(cell.placement.column).sum()
            val width = table.columnWidths.subList(cell.placement.column, cell.placement.column + cell.placement.cell.columnSpan).sum()
            target += PositionedRectangle(left, top + layout.closing / 2, width, height, cell.placement.cell.background, table.border)
            for (piece in cell.pieces) {
                if (piece.top < from - 0.001f || piece.top + piece.height > until + 0.001f) continue
                target += piece.elements.map { translate(it, left + table.cellMargins.left, top + layout.closing + table.cellMargins.top + piece.top - from) }
            }
        }
        return height
    }

    fun checkWidth(table: PdfTable, available: Float, left: Float) {
        if (left + table.leftIndent < 0 || table.leftIndent + table.columnWidths.sum() > available + 0.01f) overflow("Table exceeds its containing width")
    }

    private fun translate(element: PositionedElement, x: Float, y: Float): PositionedElement = when (element) {
        is PositionedText -> element.copy(x = element.x + x, baseline = element.baseline + y)
        is PositionedImage -> element.copy(x = element.x + x, top = element.top + y)
        is PositionedRectangle -> element.copy(x = element.x + x, top = element.top + y)
        is PositionedBox -> element.copy(x = element.x + x, top = element.top + y)
    }

    private fun overflow(message: String): Nothing = throw PdfRenderException(PdfFailureCode.CONTENT_OVERFLOW, message)
}
