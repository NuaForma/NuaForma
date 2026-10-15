package dev.nuaforma.template.internal

import dev.nuaforma.contracts.GenerationContext

/** Detection only, for rejecting content in locations which are not template processors. */
internal object ContentMarkers {
    fun contains(source: String, context: GenerationContext, valueNames: Set<String> = emptySet()): Boolean {
        context.checkActive()
        var index = 0
        var marker = false
        var quoted = false
        var escaped = false
        while (index < source.length) {
            if (index % 1024 == 0) context.checkActive()
            val character = source[index]
            if (!marker && (source.startsWith("{{", index) || source.startsWith("}}", index))) { index += 2; continue }
            if (!marker) { if (character == '{') marker = true; index++; continue }
            if (escaped) { escaped = false; index++; continue }
            if (quoted && character == '\\') { escaped = true; index++; continue }
            if (character == '"') quoted = !quoted
            if (!quoted && character == '}') marker = false
            if (!quoted && character == '|' && source.getOrNull(index - 1) != '|' && source.getOrNull(index + 1) != '|') {
                var cursor = index + 1
                while (cursor < source.length && source[cursor].isWhitespace()) cursor++
                val start = cursor
                while (cursor < source.length && (source[cursor].isLetterOrDigit() || source[cursor] == '_')) cursor++
                val name = source.substring(start, cursor)
                while (cursor < source.length && source[cursor].isWhitespace()) cursor++
                if (name.isNotEmpty() && name !in setOf("number", "date") && name !in valueNames && source.getOrNull(cursor) == '(') return true
            }
            index++
        }
        return false
    }
}
