package dev.nuaforma.template.internal

internal enum class StringOperation(val functionName: String, val minimumArguments: Int, val maximumArguments: Int) {
    CONTAINS("contains", 2, 3),
    STARTS_WITH("startsWith", 2, 3),
    ENDS_WITH("endsWith", 2, 3),
    TRIM("trim", 1, 1),
    EQUALS_IGNORE_CASE("equalsIgnoreCase", 2, 2);

    fun evaluate(arguments: List<Any?>, state: Evaluation): Any {
        fun string(index: Int): String {
            val value = arguments[index]
            state.present(value)
            return value as? String ?: state.typeFailure()
        }
        val text = string(0)
        if (this == TRIM) return trim(text, state)
        val search = string(1)
        val ignoreCase = this == EQUALS_IGNORE_CASE || (arguments.size == 3 && state.boolean(arguments[2]))
        val source = codePoints(text, ignoreCase, state)
        val target = codePoints(search, ignoreCase, state)
        return when (this) {
            CONTAINS -> contains(source, target, state)
            STARTS_WITH -> matches(source, target, 0, state)
            ENDS_WITH -> matches(source, target, source.size - target.size, state)
            EQUALS_IGNORE_CASE -> source.size == target.size && matches(source, target, 0, state)
            TRIM -> error("Trim is handled before comparison")
        }
    }

    private fun codePoints(text: String, ignoreCase: Boolean, state: Evaluation): IntArray {
        state.requireWork(text.length)
        val points = IntArray(text.length)
        var offset = 0
        var count = 0
        while (offset < text.length) {
            state.step()
            val point = Character.codePointAt(text, offset)
            points[count++] = if (ignoreCase) Character.toLowerCase(Character.toUpperCase(point)) else point
            offset += Character.charCount(point)
        }
        return points.copyOf(count)
    }

    private fun matches(source: IntArray, target: IntArray, start: Int, state: Evaluation): Boolean {
        if (start < 0 || target.size > source.size - start) return false
        for (index in target.indices) {
            state.step()
            if (source[start + index] != target[index]) return false
        }
        return true
    }

    private fun contains(source: IntArray, target: IntArray, state: Evaluation): Boolean {
        if (target.isEmpty()) return true
        if (target.size > source.size) return false
        // Prefix links keep overlapping searches linear, including adversarial repeated text.
        val prefixes = IntArray(target.size)
        var matched = 0
        for (index in 1 until target.size) {
            while (matched > 0) {
                state.step()
                if (target[index] == target[matched]) break
                matched = prefixes[matched - 1]
            }
            state.step()
            if (target[index] == target[matched]) matched++
            prefixes[index] = matched
        }
        matched = 0
        for (point in source) {
            while (matched > 0) {
                state.step()
                if (point == target[matched]) break
                matched = prefixes[matched - 1]
            }
            state.step()
            if (point == target[matched]) matched++
            if (matched == target.size) return true
        }
        return false
    }

    private fun trim(text: String, state: Evaluation): String {
        fun whitespace(index: Int): Boolean {
            state.step()
            val character = text[index]
            return Character.isWhitespace(character) || Character.isSpaceChar(character)
        }
        var first = 0
        var end = text.length
        while (first < end && whitespace(first)) first++
        while (end > first && whitespace(end - 1)) end--
        if (first == 0 && end == text.length) return text
        for (index in first until end) state.step()
        return text.substring(first, end)
    }
}
