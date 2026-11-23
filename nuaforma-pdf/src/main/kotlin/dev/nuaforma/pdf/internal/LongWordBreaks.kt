package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.PdfFailureCode
import dev.nuaforma.pdf.PdfRenderException
import dev.nuaforma.pdf.PdfTextStyle
import java.util.regex.Pattern

internal data class TextRunSlice(val run: Int, val from: Int, val until: Int, val metrics: TextMetrics)

/** Splits an oversized unshaped word without changing its source runs or grapheme clusters. */
internal class LongWordBreaks(private val runs: List<Pair<String, PdfTextStyle>>, private val measurer: TextMeasurer) {
    private val clusters: List<List<TextRunSlice>>
    private var cursor = 0

    val hasMore: Boolean get() = cursor < clusters.size

    init {
        require(runs.isNotEmpty() && runs.all { it.first.isNotEmpty() })
        if (runs.any { it.second.fontFeatures.active })
            throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Long-word breaking with font shaping is not qualified")
        val text = runs.joinToString("") { it.first }
        val matcher = graphemes.matcher(text)
        val result = mutableListOf<List<TextRunSlice>>()
        var run = 0
        var runStart = 0
        while (matcher.find()) {
            PageLayouter.checkCancellation()
            var start = matcher.start()
            val parts = mutableListOf<TextRunSlice>()
            while (start < matcher.end()) {
                PageLayouter.checkCancellation()
                while (start >= runStart + runs[run].first.length) {
                    runStart += runs[run].first.length
                    run++
                }
                val end = minOf(matcher.end(), runStart + runs[run].first.length)
                parts += slice(run, start - runStart, end - runStart)
                start = end
            }
            result += parts
        }
        clusters = result
    }

    fun next(available: Float): List<TextRunSlice> {
        val start = cursor
        var width = 0.0
        while (cursor < clusters.size) {
            PageLayouter.checkCancellation()
            val next = clusters[cursor].sumOf { it.metrics.width.toDouble() }
            if (width + next > available + .001f) break
            width += next
            cursor++
        }
        if (cursor == start) {
            val required = clusters[cursor].sumOf { it.metrics.width.toDouble() }.toFloat()
            throw PdfRenderException(PdfFailureCode.CONTENT_OVERFLOW,
                "Word cluster width exceeds line width; required=$required pt; available=$available pt")
        }
        val result = mutableListOf<TextRunSlice>()
        for (index in start until cursor) {
            PageLayouter.checkCancellation()
            for (part in clusters[index]) {
                val previous = result.lastOrNull()
                if (previous != null && previous.run == part.run && previous.until == part.from)
                    result[result.lastIndex] = previous.copy(until = part.until)
                else result += part
            }
        }
        return result.map { slice(it.run, it.from, it.until) }
    }

    private fun slice(run: Int, from: Int, until: Int): TextRunSlice =
        TextRunSlice(run, from, until, measurer.measure(runs[run].first.substring(from, until), runs[run].second))

    companion object {
        private val graphemes = Pattern.compile("\\X")
    }
}
