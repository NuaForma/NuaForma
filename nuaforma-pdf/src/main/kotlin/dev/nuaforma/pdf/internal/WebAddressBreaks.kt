package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.PdfTextStyle

/** Break opportunities within visible HTTP addresses. The original characters and run boundaries stay intact. */
internal class WebAddressBreaks private constructor(
    private val runs: List<Pair<String, PdfTextStyle>>,
    private val endpoints: List<Int>,
    private val measurer: TextMeasurer,
) {
    private var start = 0

    val hasMore: Boolean get() = start < endpoints.last()

    fun minimumWidth(): Float = width(slices(endpoints.first { it > start }))

    fun take(available: Float): List<TextRunSlice> {
        var chosen = emptyList<TextRunSlice>()
        for (end in endpoints) {
            if (end <= start) continue
            PageLayouter.checkCancellation()
            val trial = slices(end)
            if (width(trial) > available + .001f) break
            chosen = trial
        }
        start += chosen.sumOf { it.until - it.from }
        return chosen
    }

    private fun slices(end: Int): List<TextRunSlice> {
        var offset = 0
        return buildList {
            for ((index, run) in runs.withIndex()) {
                val (text, style) = run
                val from = maxOf(0, start - offset)
                val until = minOf(text.length, end - offset)
                if (from < until) add(TextRunSlice(index, from, until, measurer.measure(text.substring(from, until), style)))
                offset += text.length
                if (offset >= end) break
            }
        }
    }

    private fun width(slices: List<TextRunSlice>): Float = slices.sumOf { it.metrics.width.toDouble() }.toFloat()

    companion object {
        private val address = Regex("""https?://[A-Za-z0-9][A-Za-z0-9.:-]*(?:[/#?][A-Za-z0-9._~:/?#@!$&'()*+,;=%-]*)?""", RegexOption.IGNORE_CASE)

        fun of(runs: List<Pair<String, PdfTextStyle>>, measurer: TextMeasurer): WebAddressBreaks? {
            if (runs.sumOf { it.first.length } !in 1..2048) return null
            val text = runs.joinToString("") { it.first }
            if (!address.matches(text)) return null
            val path = text.indexOfAny(charArrayOf('/', '?', '#'), startIndex = text.indexOf("://") + 3)
            if (path < 0) return null
            val boundaries = (path until text.lastIndex).filter { text[it] == '-' || text[it] == '#' }.map { it + 1 }
            if (boundaries.isEmpty() || boundaries.size > 128) return null
            return WebAddressBreaks(runs, boundaries + text.length, measurer)
        }
    }
}
