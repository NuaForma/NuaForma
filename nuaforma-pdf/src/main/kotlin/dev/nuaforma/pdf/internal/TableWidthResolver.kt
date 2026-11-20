package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.*

internal object TableWidthResolver {

    fun resolve(table: PdfTable, measurer: TextMeasurer, availableWidth: Float): PdfTable {
        if (table.alignment == PdfTableAlignment.LEFT) return widths(table, measurer, availableWidth)
        val measured = widths(positioned(table, 0f), measurer, availableWidth)
        val remainder = availableWidth - measured.columnWidths.sum()
        val origin = if (table.alignment == PdfTableAlignment.CENTER) remainder / 2 else remainder - rightStroke(measured) / 2
        return positioned(measured, origin + table.leftIndent)
    }

    private fun positioned(table: PdfTable, indent: Float): PdfTable {
        if (!indent.isFinite() || indent !in -14_400f..14_400f)
            throw PdfRenderException(PdfFailureCode.CONTENT_OVERFLOW, "Table exceeds its containing width")
        return PdfTable(table.columnWidths, table.rows, table.cellMargins, table.border, table.repeatedHeaderRows,
            indent, table.widthPolicy, table.semantics, table.preferredWidth)
            .withRightOverflow(table.rightOverflow).withAlignment(table.alignment)
    }

    private fun rightStroke(table: PdfTable): Float = table.placements()
        .filter { it.column + it.cell.columnSpan == table.columnWidths.size }
        .maxOfOrNull { it.cell.borders?.let { borders -> borders.right?.border?.width ?: 0f } ?: (table.border?.width ?: 0f) } ?: 0f

    private fun widths(table: PdfTable, measurer: TextMeasurer, availableWidth: Float): PdfTable {
        if (table.widthPolicy == PdfTableWidthPolicy.FIXED) return table
        if (table.widthPolicy == PdfTableWidthPolicy.AUTO_FIT) return AutoTableWidths.resolve(table, measurer, availableWidth)
        val widths = MutableList(table.columnWidths.size) { 0f }
        val framed = MutableList(table.columnWidths.size) { 0f }
        for (row in table.rows) {
            PageLayouter.checkCancellation()
            for ((column, cell) in row.cells.withIndex()) {
                if (cell.columnSpan != 1 || cell.rowSpan != 1) unsupported()
                val margins = cell.margins ?: table.cellMargins
                var content = 0f
                for (block in cell.printableBlocks) {
                    val paragraph = block as? PdfParagraph ?: unsupported()
                    val format = paragraph.format
                    if (paragraph.listLabel != null || format.leftIndent != 0f || format.rightIndent != 0f ||
                        format.firstLineIndent != 0f || format.tabs != null || format.textDirection == PdfTextDirection.RIGHT_TO_LEFT) unsupported()
                    val value = paragraph.runs.joinToString("") { when (it) { is PdfTextRun -> it.text; is PdfLineBreak -> "\n"; else -> unsupported() } }
                    if (value.split('\n').any { it != it.trim() || it.any { character -> character.isISOControl() } }) unsupported()
                    val width = PageLayouter.unwrappedTextWidth(paragraph.runs, measurer)
                    if (!width.isFinite() || width < 0f) throw PdfRenderException(PdfFailureCode.CONTENT_OVERFLOW, "Invalid natural table width")
                    widths[column] = maxOf(widths[column], width)
                    content = maxOf(content, width)
                }
                framed[column] = maxOf(framed[column], framedWidth(content, margins))
            }
        }
        if (widths.any { it <= 0f }) unsupported()
        if (framed.sum() > 14_400f) overflow()
        return PdfTable(framed, table.rows,
            table.cellMargins, table.border, table.repeatedHeaderRows, table.leftIndent, semantics = table.semantics, preferredWidth = table.preferredWidth).withRightOverflow(table.rightOverflow).withAlignment(table.alignment)
    }

    private fun framedWidth(content: Float, margins: PdfMargins): Float {
        var width = content + margins.left + margins.right
        // Outward rounding prevents padding subtraction from narrowing the measured line.
        repeat(4) { if (width - margins.left - margins.right < content) width = Math.nextUp(width) }
        if (!width.isFinite() || width > 14_400f || width - margins.left - margins.right < content) overflow()
        return width
    }

    private fun unsupported(): Nothing = throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Natural table widths require unmerged text cells without indentation, controls or direction changes")

    private fun overflow(): Nothing = throw PdfRenderException(PdfFailureCode.CONTENT_OVERFLOW, "Natural table width exceeds the physical layout limit")

}
