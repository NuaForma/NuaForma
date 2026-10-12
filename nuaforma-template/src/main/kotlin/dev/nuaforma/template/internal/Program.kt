package dev.nuaforma.template.internal

import dev.nuaforma.contracts.DiagnosticCodes
import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.contracts.GenerationOptions
import dev.nuaforma.template.CompiledTemplate
import dev.nuaforma.template.TemplateFragment
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.DateTimeException

internal sealed interface Node {
    class Text(val text: String, val offset: Int, val end: Int) : Node
    class Value(val expression: Expression, val format: Format?, val offset: Int, val end: Int) : Node
    class Condition(val expression: Expression, val yes: List<Node>, val no: List<Node>, val offset: Int) : Node
    class Repeat(val expression: Expression, val alias: String, val children: List<Node>, val offset: Int) : Node
}

internal class Format(val kind: String, val pattern: String) {
    fun apply(value: Any?, state: Evaluation): String {
        state.present(value)
        if (value == null) return ""
        return try {
            when (kind) {
                "number" -> {
                    if (value !is BigDecimal) state.typeFailure()
                    DecimalFormat(pattern, DecimalFormatSymbols.getInstance(state.options.locale)).apply {
                        roundingMode = java.math.RoundingMode.HALF_EVEN
                    }.format(value)
                }
                "date" -> {
                    if (value !is String) state.typeFailure()
                    if (value.length > 128) Safety.fail(DiagnosticCodes.INVALID_REQUEST, "ISO date input exceeds the supported length")
                    val formatter = DateTimeFormatter.ofPattern(pattern, state.options.locale)
                    if (Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value)) formatter.format(LocalDate.parse(value))
                    else formatter.format(OffsetDateTime.parse(value).toInstant().atZone(state.options.timeZone))
                }
                else -> error("Unknown compiled formatter")
            }
        } catch (_: IllegalArgumentException) {
            Safety.fail(DiagnosticCodes.INVALID_REQUEST, "Value cannot be formatted with the requested format")
        } catch (_: DateTimeException) {
            Safety.fail(DiagnosticCodes.INVALID_REQUEST, "Date must be an ISO date or an ISO timestamp with an offset and match the requested format")
        }
    }
}

internal class Program(private val nodes: List<Node>, private val depth: Int) : CompiledTemplate {
    fun validateDepth(state: Evaluation, scopeDepth: Int) { state.checkDepth(scopeDepth + depth - 1) }
    fun expand(state: Evaluation, scope: Map<String, Any?>, scopeDepth: Int): List<TemplateFragment> {
        validateDepth(state, scopeDepth)
        return state.fragments { state.render(nodes, scope) }
    }
    override fun render(data: Map<String, *>, options: GenerationOptions, context: GenerationContext): String {
        context.checkActive()
        if (depth > Safety.depth(context)) Safety.limit()
        val state = Evaluation(Safety.snapshot(data, context), options, context)
        state.render(nodes, emptyMap())
        context.checkActive()
        return state.result()
    }
}

internal class Evaluation(val data: Map<String, Any?>, val options: GenerationOptions, private val context: GenerationContext) {
    private var operations = 0
    private var repetitions = 0L
    private var bytes = 0L
    private val output = StringBuilder()
    private var offset = 0
    private var captured: MutableList<TemplateFragment>? = null

    fun checkDepth(depth: Int) { if (depth > Safety.depth(context)) Safety.limit() }
    fun fragments(action: () -> Unit): List<TemplateFragment> {
        val previous = captured
        val result = mutableListOf<TemplateFragment>()
        captured = result
        val start = output.length
        return try { action(); java.util.Collections.unmodifiableList(result) }
        finally { captured = previous; output.setLength(start) }
    }
    fun scopes(value: Any?, scope: Map<String, Any?>, alias: String): List<Map<String, Any?>> = scopeSequence(value, scope, alias).toList()

    private fun scopeSequence(value: Any?, scope: Map<String, Any?>, alias: String): Sequence<Map<String, Any?>> = sequence {
        present(value)
        if (value !is List<*>) typeFailure()
        value.forEachIndexed { index, item ->
            step()
            if (++repetitions > context.limits.maxRepetitions) Safety.limit()
            val loop = mapOf("index" to BigDecimal(index), "number" to BigDecimal(index + 1),
                "first" to (index == 0), "last" to (index == value.lastIndex), "size" to BigDecimal(value.size))
            yield(scope + mapOf(alias to item, "loop" to loop))
        }
    }

    fun step() {
        context.checkActive()
        if (++operations > Safety.MAX_OPERATIONS) Safety.limit()
    }
    fun requireWork(units: Int) {
        context.checkActive()
        if (units > Safety.MAX_OPERATIONS - operations) Safety.limit()
    }
    fun present(value: Any?) { if (value === Missing) Safety.fail(DiagnosticCodes.MISSING_VALUE, "Required template data is missing", offset) }
    fun typeFailure(): Nothing = Safety.fail(DiagnosticCodes.INVALID_REQUEST, "Template data has an incompatible type", offset)
    fun boolean(value: Any?): Boolean { present(value); return value as? Boolean ?: typeFailure() }

    fun render(nodes: List<Node>, scope: Map<String, Any?>) {
        for (node in nodes) {
            step()
            when (node) {
                is Node.Text -> append(node.text, node.offset, node.end, true)
                is Node.Value -> {
                    offset = node.offset
                    val value = node.expression.evaluate(this, scope)
                    append(node.format?.apply(value, this) ?: scalar(value), node.offset, node.end, false)
                }
                is Node.Condition -> {
                    offset = node.offset
                    render(if (boolean(node.expression.evaluate(this, scope))) node.yes else node.no, scope)
                }
                is Node.Repeat -> {
                    offset = node.offset
                    val value = node.expression.evaluate(this, scope)
                    scopeSequence(value, scope, node.alias).forEach { render(node.children, it) }
                }
            }
        }
    }

    private fun scalar(value: Any?): String {
        present(value)
        return when (value) {
            null -> ""
            is String -> value
            is Boolean -> value.toString()
            is BigDecimal -> value.stripTrailingZeros().toPlainString()
            else -> typeFailure()
        }
    }

    private fun append(text: String, start: Int, end: Int, literal: Boolean) {
        val size = Safety.utf8Length(text, context, DiagnosticCodes.INVALID_REQUEST)
        val limit = minOf(context.limits.maxOutputBytes, context.limits.maxExpandedBytes)
        if (size > limit - bytes || text.length.toLong() + output.length > Int.MAX_VALUE - 8L) Safety.limit()
        bytes += size
        captured?.let {
            if (it.size >= Safety.MAX_NODES) Safety.limit()
            it.add(TemplateFragment(start, end, text, literal))
        }
        output.append(text)
    }
    fun result(): String = output.toString()
}
