package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.*

internal object TableWidthResolver {

    fun resolve(table: PdfTable, measurer: TextMeasurer, availableWidth: Float): PdfTable {
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
                for (block in cell.blocks) {
                    val paragraph = block as? PdfParagraph ?: unsupported()
                    val format = paragraph.format
                    if (paragraph.listLabel != null || format.leftIndent != 0f || format.rightIndent != 0f ||
                        format.firstLineIndent != 0f || format.tabs != null || format.textDirection == PdfTextDirection.RIGHT_TO_LEFT) unsupported()
                    val text = paragraph.runs.map { it as? PdfTextRun ?: unsupported() }
                    val value = text.joinToString("") { it.text }
                    if (value != value.trim() || value.any { it.isISOControl() }) unsupported()
                    val width = PageLayouter.unwrappedTextWidth(text, measurer)
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
            table.cellMargins, table.border, table.repeatedHeaderRows, table.leftIndent, semantics = table.semantics, preferredWidth = table.preferredWidth).withRightOverflow(table.rightOverflow)
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
