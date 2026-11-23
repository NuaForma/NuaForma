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

    /** Continue an oversized visible link at a character boundary when no preferred boundary fits. */
    fun takeCharacters(available: Float): List<TextRunSlice> {
        val remainder = slices(endpoints.last())
        val remainingRuns = remainder.map { part ->
            val (text, style) = runs[part.run]
            text.substring(part.from, part.until) to style
        }
        val chosen = LongWordBreaks(remainingRuns, measurer).next(available).map { part ->
            val origin = remainder[part.run]
            part.copy(run = origin.run, from = origin.from + part.from, until = origin.from + part.until)
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

        private val simplePath = Regex("""https?://[A-Za-z0-9][A-Za-z0-9.:-]*(?:/[A-Za-z0-9._~%]+)+/?""", RegexOption.IGNORE_CASE)

        private val invalidEscape = Regex("%(?![0-9A-Fa-f]{2})")

        fun isSimplePath(fragments: List<String>): Boolean {
            if (fragments.sumOf(String::length) !in 1..2_048) return false
            val address = fragments.joinToString("")
            return simplePath.matches(address) && !invalidEscape.containsMatchIn(address)
        }

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
