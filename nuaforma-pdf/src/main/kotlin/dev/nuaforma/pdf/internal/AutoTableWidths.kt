package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.*

/** Request-local intrinsic measurement and bounded preferred-width negotiation. */
internal object AutoTableWidths {
    private data class Constraint(val placement: TablePlacement, val minimum: Float, val maximum: Float)

    fun resolve(table: PdfTable, measurer: TextMeasurer, availableWidth: Float): PdfTable {
        val available = minOf(14_400f, availableWidth - table.leftIndent + table.rightOverflow)
        if (!available.isFinite() || available <= 0f) overflow()
        val constraints = constraints(table, measurer, 0)
        val padding = table.cellMargins.left + table.cellMargins.right
        val variedMargins = table.rows.any { row -> row.cells.any { it.margins != null && it.margins != table.cellMargins } }
        val minimum = MutableList(table.columnWidths.size) { if (variedMargins) .01f else padding + .01f }
        val maximum = minimum.toMutableList()
        for (constraint in constraints.sortedBy { it.placement.cell.columnSpan }) {
            satisfy(minimum, constraint.placement, constraint.minimum, table.columnWidths)
            satisfy(maximum, constraint.placement, constraint.maximum, table.columnWidths)
        }
        maximum.indices.forEach { maximum[it] = maxOf(maximum[it], minimum[it]) }
        if (minimum.sum() > available + .001f) overflow()
        val hasPercent = constraints.any { it.placement.cell.preferredWidth?.percent != null }
        val physical = preferred(table.preferredWidth, availableWidth)
        val reference = minOf(available, physical ?: if (hasPercent) available else maximum.sum())
        val preferred = minimum.toMutableList()
        val specified = BooleanArray(minimum.size)
        for (constraint in constraints) {
            val placement = constraint.placement
            val value = preferred(placement.cell.preferredWidth, reference) ?: continue
            satisfy(preferred, placement, value, table.columnWidths)
            for (column in placement.column until placement.column + placement.cell.columnSpan) specified[column] = true
        }
        for (column in preferred.indices) if (!specified[column]) preferred[column] = maximum[column]
        val target = minOf(available, maxOf(minimum.sum(), physical ?: preferred.sum()))
        val widths = fit(preferred, minimum, target)
        return PdfTable(widths, table.rows, table.cellMargins, table.border, table.repeatedHeaderRows,
            table.leftIndent, semantics = table.semantics, preferredWidth = table.preferredWidth).withRightOverflow(table.rightOverflow)
    }

    private fun preferred(value: PdfPreferredWidth?, reference: Float): Float? = value?.let {
        it.points ?: reference * requireNotNull(it.percent) / 100f
    }

    private fun constraints(table: PdfTable, measurer: TextMeasurer, depth: Int): List<Constraint> {
        if (depth > 1) unsupported("Only one nested table level is supported")
        return table.placements().map { placement ->
            PageLayouter.checkCancellation()
            val margins = placement.cell.margins ?: table.cellMargins
            val padding = margins.left + margins.right
            var minimum = 0f
            var maximum = 0f
            for (block in placement.cell.blocks) {
                val widths = when (block) {
                    is PdfParagraph -> PageLayouter.intrinsicWidths(block, measurer)
                    is PdfTable -> {
                        if (block.widthPolicy == PdfTableWidthPolicy.FIXED) block.columnWidths.sum().let { it to it }
                        else {
                            val nested = constraints(block, measurer, depth + 1)
                            val variedMargins = block.rows.any { row -> row.cells.any { it.margins != null && it.margins != block.cellMargins } }
                            val low = MutableList(block.columnWidths.size) { if (variedMargins) .01f else block.cellMargins.left + block.cellMargins.right + .01f }
                            val high = low.toMutableList()
                            for (item in nested.sortedBy { it.placement.cell.columnSpan }) {
                                satisfy(low, item.placement, item.minimum, block.columnWidths)
                                satisfy(high, item.placement, item.maximum, block.columnWidths)
                            }
                            low.sum() to maxOf(low.sum(), high.sum())
                        }
                    }
                    else -> unsupported("Unsupported automatic table cell block")
                }
                minimum = maxOf(minimum, widths.first)
                maximum = maxOf(maximum, widths.second)
            }
            if (!minimum.isFinite() || !maximum.isFinite() || minimum + padding > 14_400f) overflow()
            Constraint(placement, minimum + padding + .001f, maximum + padding + .001f)
        }
    }

    private fun satisfy(widths: MutableList<Float>, placement: TablePlacement, required: Float, weights: List<Float>) {
        val columns = placement.column until placement.column + placement.cell.columnSpan
        val deficit = required - columns.sumOf { widths[it].toDouble() }.toFloat()
        if (deficit <= 0f) return
        val weight = columns.sumOf { weights[it].toDouble() }.toFloat()
        for (column in columns) widths[column] += deficit * if (weight > 0f) weights[column] / weight else 1f / placement.cell.columnSpan
    }

    private fun fit(preferred: List<Float>, minimum: List<Float>, target: Float): List<Float> {
        val total = preferred.sum()
        val base = minimum.sum()
        if (total > target) {
            val scale = if (total > base) (target - base) / (total - base) else 0f
            return preferred.indices.map { minimum[it] + (preferred[it] - minimum[it]) * maxOf(0f, scale) }
        }
        val extra = target - total
        return preferred.map { it + extra * if (total > 0f) it / total else 1f / preferred.size }
    }

    private fun overflow(): Nothing = throw PdfRenderException(PdfFailureCode.CONTENT_OVERFLOW, "Automatic table minimum widths exceed the containing width or layout limit")

    private fun unsupported(message: String): Nothing = throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, message)

}
