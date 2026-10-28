package dev.nuaforma.template.internal

/** A quote closes at an expression boundary, regardless of the opening quote's shape. */
internal object QuotedText {
    fun isQuote(character: Char): Boolean = character in "\"'\u2018\u2019\u201a\u201b\u201c\u201d\u201e\u201f\u00ab\u00bb\u2039\u203a"

    fun end(text: String, start: Int): Int {
        var cursor = start + 1
        val limit = minOf(text.length, start + 4097)
        val format = FORMAT_ARGUMENT.containsMatchIn(text.substring((start - 4096).coerceAtLeast(0), start))
        while (cursor < limit) {
            val character = text[cursor++]
            if (character == '\\') { cursor++; continue }
            if (isQuote(character) && boundary(text, cursor, format)) return cursor
        }
        return -1
    }

    private fun boundary(text: String, after: Int, format: Boolean): Boolean {
        var cursor = after
        val limit = minOf(text.length, after + 4096)
        while (cursor < limit && text[cursor].isWhitespace()) cursor++
        return cursor == text.length || cursor < limit && text[cursor] in if (format) ")}" else "),]}|&?=!<>"
    }

    private val FORMAT_ARGUMENT = Regex("(?:^|[^A-Za-z0-9_])(?:date|number)\\(\\s*$")
}
