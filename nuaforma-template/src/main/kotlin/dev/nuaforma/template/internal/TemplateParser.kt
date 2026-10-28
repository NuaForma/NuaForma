package dev.nuaforma.template.internal

import dev.nuaforma.contracts.DiagnosticCodes
import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.contracts.GenerationException
import dev.nuaforma.contracts.RejectionReason
import dev.nuaforma.template.CompiledTemplate
import dev.nuaforma.template.TemplatePipes
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.format.DateTimeFormatter
import java.util.Locale

internal class TemplateParser(private val source: String, private val context: GenerationContext, private val captureSpans: Boolean = false,
                              private val contentNames: Set<String> = emptySet(), private val valuePipes: TemplatePipes = TemplatePipes.of(emptyList())) {
    private data class Marker(val text: String, val offset: Int, val contentOffset: Int, val end: Int)
    private var markerStart = 0
    private var markerEnd = 0
    private var cursor = 0
    private var nodes = 0
    private var maxDepth = 1

    fun compile(): CompiledTemplate = try { compileProgram() } catch (error: GenerationException) {
        val failure = error.diagnostics.firstOrNull()?.location?.removePrefix("template:")?.toIntOrNull()
        val start = failure?.takeIf { it < markerStart }?.coerceAtLeast(0) ?: markerStart
        val end = if (start == markerStart && markerEnd > start) markerEnd else minOf(source.length, start + 256)
        throw Safety.enrich(error, source.substring(start, end), context, start)
    }

    private fun compileProgram(): CompiledTemplate {
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
                            if (stop == null) Safety.syntax(marker.offset, RejectionReason.UNCLOSED_TEMPLATE_BLOCK)
                            val no = if (stop.text == "#else") {
                                val (alternative, end) = sequence(setOf("/if"), depth + 1)
                                if (end == null) Safety.syntax(marker.offset, RejectionReason.UNCLOSED_TEMPLATE_BLOCK)
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
                            if (stop == null) Safety.syntax(marker.offset, RejectionReason.UNCLOSED_TEMPLATE_BLOCK)
                            add(Node.Repeat(expression, alias, children, marker.offset))
                        }
                        marker.text.startsWith('#') || marker.text.startsWith('/') -> Safety.invalid(marker.offset)
                        else -> {
                            val segments = pipeline(marker.text, marker.contentOffset)
                            val initial = expression(segments.first().text, segments.first().offset, depth)
                            val content = segments.drop(1).mapNotNull { CONTENT.matchEntire(it.text.trim()) }
                                .firstOrNull { it.groupValues[1] in contentNames }
                            if (content != null) {
                                if (segments.size != 2) Safety.invalid(marker.offset)
                                val arguments = arguments(content.groupValues[2], segments[1].offset + segments[1].text.indexOf('(') + 1, depth)
                                add(Node.Content(initial, content.groupValues[1], arguments, marker.offset, marker.end))
                            } else {
                                val steps = segments.drop(1).map { segment ->
                                    val leading = segment.text.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                                    val segmentOffset = segment.offset + leading
                                    val match = CONTENT.matchEntire(segment.text.trim()) ?: Safety.syntax(segmentOffset, RejectionReason.INVALID_PIPE_EXPRESSION)
                                    val name = match.groupValues[1]
                                    if (name in setOf("number", "date")) PipeStep.Builtin(format(segment.text.trim(), segmentOffset))
                                    else {
                                        if (name !in valuePipes.registrations) Safety.syntax(segmentOffset, RejectionReason.INVALID_PIPE_EXPRESSION)
                                        PipeStep.Custom(name, arguments(match.groupValues[2], segmentOffset + match.groups[2]!!.range.first, depth))
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

    private fun arguments(text: String, offset: Int, depth: Int): List<Expression> =
        ExpressionParser(text, offset, context).arguments().also { arguments ->
            arguments.forEach { maxDepth = maxOf(maxDepth, depth + it.depth - 1) }
            if (maxDepth > Safety.depth(context)) Safety.limit()
        }

    private data class Segment(val text: String, val offset: Int)

    private fun pipeline(text: String, offset: Int): List<Segment> {
        val result = mutableListOf<Segment>()
        var start = 0
        var index = 0
        var nesting = 0
        while (index < text.length) {
            val character = text[index]
            if (QuotedText.isQuote(character)) {
                index = QuotedText.end(text, index).takeIf { it >= 0 }
                    ?: Safety.syntax(offset + index, RejectionReason.UNCLOSED_EXPRESSION_STRING)
                continue
            }
            if (character == '(' || character == '[') nesting++
            if (character == ')' || character == ']') nesting--
            if (nesting == 0 && character == '|' && text.getOrNull(index - 1) != '|' && text.getOrNull(index + 1) != '|') {
                if (result.size >= 32) Safety.limit()
                result.add(Segment(text.substring(start, index), offset + start))
                start = index + 1
            }
            index++
        }
        result.add(Segment(text.substring(start), offset + start))
        return result
    }

    private fun marker(): Marker {
        val offset = cursor++
        markerStart = offset
        markerEnd = minOf(source.length, offset + 4098)
        val start = cursor
        while (cursor < source.length) {
            if (cursor - start > 4096) Safety.limit()
            val character = source[cursor]
            if (QuotedText.isQuote(character)) {
                cursor = QuotedText.end(source, cursor).takeIf { it >= 0 }
                    ?: Safety.syntax(cursor, RejectionReason.UNCLOSED_EXPRESSION_STRING)
                continue
            }
            cursor++
            if (character == '}') {
                markerEnd = cursor
                val raw = source.substring(start, cursor - 1)
                val leading = raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                return Marker(raw.trim(), offset, start + leading, cursor)
            }
            if (character == '{') Safety.invalid(cursor - 1)
        }
        Safety.syntax(offset, RejectionReason.UNCLOSED_TEMPLATE_MARKER)
    }

    private fun format(text: String, offset: Int): Format {
        val opening = text.indexOf('(')
        val kind = text.substring(0, opening)
        val start = (opening + 1 until text.length).firstOrNull { !text[it].isWhitespace() }
            ?: Safety.syntax(offset + opening + 1, RejectionReason.UNCLOSED_EXPRESSION_STRING)
        if (!QuotedText.isQuote(text[start])) Safety.syntax(offset + start, RejectionReason.INVALID_FORMAT_PATTERN)
        val end = QuotedText.end(text, start)
        if (end < 0) Safety.syntax(offset + start, RejectionReason.UNCLOSED_EXPRESSION_STRING)
        if (text.substring(end).trim() != ")") Safety.syntax(offset + end, RejectionReason.EXPECTED_EXPRESSION_DELIMITER)
        val pattern = text.substring(start + 1, end - 1).let { raw ->
            if (kind == "date") raw.map { if (QuotedText.isQuote(it)) '\'' else it }.joinToString("") else raw
        }
        if (pattern.isEmpty() || pattern.length > 64 || '\\' in pattern) Safety.syntax(offset + start + 1, RejectionReason.INVALID_FORMAT_PATTERN)
        try {
            when (kind) {
                "number" -> {
                    if (!Regex("[0#.,%;E+\\-]+(;[0#.,%;E+\\-]+)?").matches(pattern)) Safety.syntax(offset + start + 1, RejectionReason.INVALID_FORMAT_PATTERN)
                    DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.ROOT))
                }
                "date" -> DateTimeFormatter.ofPattern(pattern, Locale.ROOT)
                else -> Safety.syntax(offset + start + 1, RejectionReason.INVALID_FORMAT_PATTERN)
            }
        } catch (_: IllegalArgumentException) { Safety.syntax(offset + start + 1, RejectionReason.INVALID_FORMAT_PATTERN) }
        return Format(kind, pattern)
    }

    private companion object {
        val EACH = Regex("(.+)\\s+as\\s+([A-Za-z_][A-Za-z0-9_]*)")
        val CONTENT = Regex("([A-Za-z_][A-Za-z0-9_]*)\\((.*)\\)", RegexOption.DOT_MATCHES_ALL)
    }
}
