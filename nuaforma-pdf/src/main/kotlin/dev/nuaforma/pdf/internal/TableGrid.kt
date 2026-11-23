package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.*

internal data class TablePlacement(val row: Int, val column: Int, val cell: PdfTableCell)
internal fun PdfTable.placements(): List<TablePlacement> {
    val occupied = Array(rows.size) { BooleanArray(columnWidths.size) }
    val result = mutableListOf<TablePlacement>()
    rows.forEachIndexed { rowIndex, row ->
        val first = row.omittedLeadingColumns
        val end = columnWidths.size - row.omittedTrailingColumns
        require(first < end && (first == 0 && end == columnWidths.size || widthPolicy == PdfTableWidthPolicy.FIXED)) {
            "Omitted columns require a nonempty fixed table grid"
        }
        var column = first
        for (cell in row.cells) {
            while (column < end && occupied[rowIndex][column]) column++
            require(column + cell.columnSpan <= end && rowIndex + cell.rowSpan <= rows.size) { "Cell span exceeds grid" }
            require(rowIndex >= repeatedHeaderRows || rowIndex + cell.rowSpan <= repeatedHeaderRows) { "Cell span crosses header boundary" }
            for (r in rowIndex until rowIndex + cell.rowSpan) for (c in column until column + cell.columnSpan) {
                require(!occupied[r][c]) { "Overlapping cell spans" }
                occupied[r][c] = true
            }
            result += TablePlacement(rowIndex, column, cell)
            column += cell.columnSpan
        }
        require(occupied[rowIndex].indices.all { index -> occupied[rowIndex][index] == (index in first until end) }) {
            if (first == 0 && end == columnWidths.size) "Uncovered table grid" else "Table grid occupancy conflicts with omitted columns"
        }
    }
    require(rows.none { it.allowSplit } || result.none { it.cell.rowSpan > 1 || it.cell.blocks.any { b -> b is PdfTable } }) { "Combined split/merged/nested rows are outside the trial profile" }
    return result
}
