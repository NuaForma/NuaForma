package dev.nuaforma.template.internal

import dev.nuaforma.contracts.DiagnosticCodes
import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.template.CompiledTemplate
import dev.nuaforma.template.TemplatePipes
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.format.DateTimeFormatter
import java.util.Locale

internal class TemplateParser(private val source: String, private val context: GenerationContext, private val captureSpans: Boolean = false,
                              private val contentNames: Set<String> = emptySet(), private val valuePipes: TemplatePipes = TemplatePipes.of(emptyList())) {
    private data class Marker(val text: String, val offset: Int, val contentOffset: Int, val end: Int)
    private var cursor = 0
    private var nodes = 0
    private var maxDepth = 1

    fun compile(): CompiledTemplate {
        context.checkActive()
        if (Safety.utf8Length(source, context, DiagnosticCodes.INVALID_TEMPLATE) > context.limits.maxTemplateBytes) Safety.limit()
        val (program, stop) = sequence(emptySet(), 1)
        if (stop != null) Safety.invalid(stop.offset)
        context.checkActive()
        return Program(program, maxDepth, valuePipes)
    }

    private fun sequence(stops: Set<String>, depth: Int): Pair<List<Node>, Marker?> {
        if (depth > Safety.depth(context)) Safety.limit()
        maxDepth = maxOf(maxDepth, depth)
        val result = mutableListOf<Node>()
        val literal = StringBuilder()
        var literalStart = cursor
        fun add(node: Node) { if (++nodes > Safety.MAX_NODES) Safety.limit(); result.add(node) }
        fun flush() { if (literal.isNotEmpty()) { add(Node.Text(literal.toString(), literalStart, cursor)); literal.setLength(0) } }
        while (cursor < source.length) {
            if (cursor % 1024 == 0) context.checkActive()
            when {
                source.startsWith("{{", cursor) -> {
                    if (captureSpans) { flush(); add(Node.Text("{", cursor, cursor + 2)) }
                    else { if (literal.isEmpty()) literalStart = cursor; literal.append('{') }
                    cursor += 2
                }
                source.startsWith("}}", cursor) -> {
                    if (captureSpans) { flush(); add(Node.Text("}", cursor, cursor + 2)) }
                    else { if (literal.isEmpty()) literalStart = cursor; literal.append('}') }
                    cursor += 2
                }
                source[cursor] == '}' -> Safety.invalid(cursor)
                source[cursor] != '{' -> { if (literal.isEmpty()) literalStart = cursor; literal.append(source[cursor++]) }
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
                            val segments = pipeline(marker.text)
                            val initial = expression(segments.first(), marker.contentOffset, depth)
                            val content = segments.drop(1).mapNotNull { CONTENT.matchEntire(it.trim()) }
                                .firstOrNull { it.groupValues[1] in contentNames }
                            if (content != null) {
                                if (segments.size != 2) Safety.invalid(marker.offset)
                                val arguments = arguments(content.groupValues[2], marker, depth)
                                add(Node.Content(initial, content.groupValues[1], arguments, marker.offset, marker.end))
                            } else {
                                val steps = segments.drop(1).map { segment ->
                                    val match = CONTENT.matchEntire(segment.trim()) ?: Safety.invalid(marker.offset)
                                    val name = match.groupValues[1]
                                    if (name in setOf("number", "date")) PipeStep.Builtin(format(segment.trim(), marker.offset))
                                    else {
                                        if (name !in valuePipes.registrations) Safety.invalid(marker.offset)
                                        PipeStep.Custom(name, arguments(match.groupValues[2], marker, depth))
                                    }
                                }
                                val value = if (steps.isEmpty()) initial else Expression.Piped(initial, steps)
                                maxDepth = maxOf(maxDepth, depth + value.depth - 1)
                                if (maxDepth > Safety.depth(context)) Safety.limit()
                                add(Node.Value(value, null, marker.offset, marker.end))
                            }
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

    private fun arguments(text: String, marker: Marker, depth: Int): List<Expression> =
        ExpressionParser(text, marker.contentOffset, context).arguments().also { arguments ->
            arguments.forEach { maxDepth = maxOf(maxDepth, depth + it.depth - 1) }
            if (maxDepth > Safety.depth(context)) Safety.limit()
        }

    private fun pipeline(text: String): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        var quoted = false
        var escaped = false
        var nesting = 0
        for (index in text.indices) {
            val character = text[index]
            if (escaped) { escaped = false; continue }
            if (quoted && character == '\\') { escaped = true; continue }
            if (character == '"') quoted = !quoted
            if (quoted) continue
            if (character == '(' || character == '[') nesting++
            if (character == ')' || character == ']') nesting--
            if (nesting == 0 && character == '|' && text.getOrNull(index - 1) != '|' && text.getOrNull(index + 1) != '|') {
                if (result.size >= 32) Safety.limit()
                result.add(text.substring(start, index))
                start = index + 1
            }
        }
        result.add(text.substring(start))
        return result
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
                return Marker(raw.trim(), offset, start + leading, cursor)
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
        val CONTENT = Regex("([A-Za-z_][A-Za-z0-9_]*)\\((.*)\\)", RegexOption.DOT_MATCHES_ALL)
    }
}
