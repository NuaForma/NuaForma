package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.*

/** Per-call bounded lookahead. The final paragraph may continue after its protected start. */
internal object ParagraphKeeps {
    data class Group(val minimum: Float, val lastIndex: Int)

    fun measure(blocks: List<PdfBlock>, start: Int, paragraph: (Int) -> FlowLayouter.MeasuredParagraph,
                measurer: TextMeasurer): Group? {
        var full = 0f
        var minimum = 0f
        var previousAfter = 0f
        var index = start
        while (index < blocks.size) {
            PageLayouter.checkCancellation()
            when (val block = blocks[index]) {
                is PdfParagraph -> {
                    if (index > start && block.format.pageBreakBefore) return if (index > start + 1) Group(minimum, index - 1) else null
                    val measured = paragraph(index)
                    val before = if (index == start) 0f else if (block.format.collapseSpacing) maxOf(0f, block.spaceBefore - previousAfter) else block.spaceBefore
                    val prefix = full + before
                    val count = measured.lines.size
                    full = prefix + count * measured.lineHeight
                    val protectedLines = when {
                        block.format.keepLines || block.format.keepWithNext -> count
                        !block.format.widowControl -> minOf(count, 1)
                        count == 3 -> 3 // Neither 1+2 nor 2+1 satisfies widow/orphan control.
                        else -> minOf(count, 2)
                    }
                    minimum = prefix + protectedLines * measured.lineHeight
                    if (!block.format.keepWithNext || index == blocks.lastIndex) return if (index == start) null else Group(minimum, index)
                    previousAfter = block.spaceAfter
                    full += block.spaceAfter
                }
                is PdfImage -> return Group(full + block.height, index)
                is PdfTable -> {
                    val measured = TableLayouter.measure(block, measurer)
                    val first = block.repeatedHeaderRows
                    if (block.rows[first].allowSplit || block.rows[first].cells.any { it.rowSpan > 1 }) unsupported()
                    val height = measured.offsets[first + 1] + measured.closing
                    return Group(full + height, index)
                }
                is PdfTextBox -> unsupported()
            }
            index++
        }
        return null
    }
    private fun unsupported(): Nothing = throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Unsupported keep-with-next target")
}
