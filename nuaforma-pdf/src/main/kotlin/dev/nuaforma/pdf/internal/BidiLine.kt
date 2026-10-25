package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.*
import java.text.Bidi

internal data class LogicalTextSlice(val text: String, val style: PdfTextStyle, val sourceStart: Int)
internal data class VisualTextRun(val text: String, val style: PdfTextStyle)

/** Resolve each physical line using its complete paragraph context, retaining font/style boundaries. */
internal object BidiLine {
    private data class Token(val text: String, val slice: Int, val level: Byte)

    fun visualRuns(paragraph: Bidi, slices: List<LogicalTextSlice>): List<VisualTextRun> {
        val start = slices.first().sourceStart
        val end = slices.last().let { it.sourceStart + it.text.length }
        val line = paragraph.createLineBidi(start, end)
        val tokens = mutableListOf<Token>()
        for ((index, slice) in slices.withIndex()) {
            var offset = 0
            while (offset < slice.text.length) {
                PageLayouter.checkCancellation()
                val point = slice.text.codePointAt(offset)
                val length = Character.charCount(point)
                val level = line.getLevelAt(slice.sourceStart + offset - start).toByte()
                if (level.toInt() and 1 != 0 && Character.isMirrored(point))
                    throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Mirrored punctuation is outside the qualified bidi profile")
                tokens += Token(slice.text.substring(offset, offset + length), index, level)
                offset += length
            }
        }
        val visual = tokens.toTypedArray()
        Bidi.reorderVisually(tokens.map { it.level }.toByteArray(), 0, visual, 0, visual.size)
        val result = mutableListOf<VisualTextRun>()
        var slice = -1
        val text = StringBuilder()
        fun flush() {
            if (slice >= 0) result += VisualTextRun(text.toString(), slices[slice].style)
            text.setLength(0)
        }
        for (token in visual) {
            if (token.slice != slice) { flush(); slice = token.slice }
            text.append(token.text)
        }
        flush()
        return result
    }
}
