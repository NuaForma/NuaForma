package dev.nuaforma.template.internal

import dev.nuaforma.contracts.DiagnosticCodes
import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.template.CompiledTemplate
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.format.DateTimeFormatter
import java.util.Locale

internal class TemplateParser(private val source: String, private val context: GenerationContext) {
    private data class Marker(val text: String, val offset: Int, val contentOffset: Int)
    private var cursor = 0
    private var nodes = 0
    private var maxDepth = 1

    fun compile(): CompiledTemplate {
        context.checkActive()
        if (Safety.utf8Length(source, context, DiagnosticCodes.INVALID_TEMPLATE) > context.limits.maxTemplateBytes) Safety.limit()
        val (program, stop) = sequence(emptySet(), 1)
        if (stop != null) Safety.invalid(stop.offset)
        context.checkActive()
        return Program(program, maxDepth)
    }

    private fun sequence(stops: Set<String>, depth: Int): Pair<List<Node>, Marker?> {
        if (depth > Safety.depth(context)) Safety.limit()
        maxDepth = maxOf(maxDepth, depth)
        val result = mutableListOf<Node>()
        val literal = StringBuilder()
        fun add(node: Node) { if (++nodes > Safety.MAX_NODES) Safety.limit(); result.add(node) }
        fun flush() { if (literal.isNotEmpty()) { add(Node.Text(literal.toString())); literal.setLength(0) } }
        while (cursor < source.length) {
            if (cursor % 1024 == 0) context.checkActive()
            when {
                source.startsWith("{{", cursor) -> { literal.append('{'); cursor += 2 }
                source.startsWith("}}", cursor) -> { literal.append('}'); cursor += 2 }
                source[cursor] == '}' -> Safety.invalid(cursor)
                source[cursor] != '{' -> literal.append(source[cursor++])
                else -> {
                    flush()
                    val marker = marker()
                    if (marker.text in stops) return result.toList() to marker
                    when {
                        marker.text.startsWith("#if ") -> {
                            val expression = expression(marker.text.substring(4), marker.contentOffset + 4, depth)
                            val (yes, stop) = sequence(setOf("#else", "/if"), depth + 1)
                            if (stop == null) Safety.invalid(marker.offset)
                            val no = if (stop.text == "#else") {
                                val (alternative, end) = sequence(setOf("/if"), depth + 1)
                                if (end == null) Safety.invalid(marker.offset)
                                alternative
                            } else emptyList()
                            add(Node.Condition(expression, yes, no, marker.offset))
                        }
                        marker.text.startsWith("#each ") -> {
                            val match = EACH.matchEntire(marker.text.substring(6)) ?: Safety.invalid(marker.offset)
                            val alias = match.groupValues[2]
                            if (alias in setOf("data", "loop", "true", "false", "null")) Safety.invalid(marker.offset)
                            val expression = expression(match.groupValues[1], marker.contentOffset + 6, depth)
                            val (children, stop) = sequence(setOf("/each"), depth + 1)
                            if (stop == null) Safety.invalid(marker.offset)
                            add(Node.Repeat(expression, alias, children, marker.offset))
                        }
                        marker.text.startsWith('#') || marker.text.startsWith('/') -> Safety.invalid(marker.offset)
                        else -> {
                            val pipe = formatPosition(marker.text)
                            val value = if (pipe < 0) marker.text else marker.text.substring(0, pipe)
                            val format = if (pipe < 0) null else format(marker.text.substring(pipe + 1), marker.offset)
                            add(Node.Value(expression(value, marker.contentOffset, depth), format, marker.offset))
                        }
                    }
                }
            }
        }
        flush()
        return result.toList() to null
    }

    private fun expression(text: String, offset: Int, depth: Int): Expression {
        val expression = ExpressionParser(text, offset, context).parse()
        maxDepth = maxOf(maxDepth, depth + expression.depth - 1)
        if (maxDepth > Safety.depth(context)) Safety.limit()
        return expression
    }

    private fun marker(): Marker {
        val offset = cursor++
        val start = cursor
        var quoted = false
        var escaped = false
        while (cursor < source.length) {
            if (cursor - start > 4096) Safety.limit()
            val character = source[cursor++]
            if (escaped) { escaped = false; continue }
            if (quoted && character == '\\') { escaped = true; continue }
            if (character == '"') quoted = !quoted
            if (!quoted && character == '}') {
                val raw = source.substring(start, cursor - 1)
                val leading = raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                return Marker(raw.trim(), offset, start + leading)
            }
            if (!quoted && character == '{') Safety.invalid(offset)
        }
        Safety.invalid(offset)
    }

    private fun formatPosition(text: String): Int {
        var quoted = false
        var escaped = false
        for (index in text.indices) {
            val character = text[index]
            if (escaped) { escaped = false; continue }
            if (quoted && character == '\\') { escaped = true; continue }
            if (character == '"') quoted = !quoted
            if (!quoted && character == '|' && text.getOrNull(index - 1) != '|' && text.getOrNull(index + 1) != '|') return index
        }
        return -1
    }

    private fun format(text: String, offset: Int): Format {
        val match = FORMAT.matchEntire(text.trim()) ?: Safety.invalid(offset)
        val kind = match.groupValues[1]
        val pattern = match.groupValues[2]
        if (pattern.isEmpty() || pattern.length > 64) Safety.invalid(offset)
        try {
            when (kind) {
                "number" -> {
                    if (!Regex("[0#.,%;E+\\-]+(;[0#.,%;E+\\-]+)?").matches(pattern)) Safety.invalid(offset)
                    DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.ROOT))
                }
                "date" -> DateTimeFormatter.ofPattern(pattern, Locale.ROOT)
                else -> Safety.invalid(offset)
            }
        } catch (_: IllegalArgumentException) { Safety.invalid(offset) }
        return Format(kind, pattern)
    }

    private companion object {
        val EACH = Regex("(.+)\\s+as\\s+([A-Za-z_][A-Za-z0-9_]*)")
        val FORMAT = Regex("(number|date)\\(\\s*\"([^\"\\\\]*)\"\\s*\\)")
    }
}
