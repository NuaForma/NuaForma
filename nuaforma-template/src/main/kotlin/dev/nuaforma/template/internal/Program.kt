package dev.nuaforma.template.internal

import dev.nuaforma.contracts.RejectionReason
import dev.nuaforma.contracts.DiagnosticCodes
import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.contracts.GenerationOptions
import dev.nuaforma.template.CompiledTemplate
import dev.nuaforma.template.TemplateFragment
import dev.nuaforma.template.ContentTemplateFragment
import dev.nuaforma.contracts.ContentResult
import dev.nuaforma.template.TemplatePipes
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
    class Content(val expression: Expression, val name: String, val arguments: List<Expression>, val offset: Int, val end: Int) : Node
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
                    if (value.length > 128) Safety.fail(DiagnosticCodes.INVALID_REQUEST, RejectionReason.ISO_DATE_INPUT_EXCEEDS_THE_SUPPORTED_LENGTH)
                    val formatter = DateTimeFormatter.ofPattern(pattern, state.options.locale)
                    if (Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value)) formatter.format(LocalDate.parse(value))
                    else formatter.format(OffsetDateTime.parse(value).toInstant().atZone(state.options.timeZone))
                }
                else -> error("Unknown compiled formatter")
            }
        } catch (_: IllegalArgumentException) {
            state.pipeRejected(kind, "Value does not match the requested format")
            Safety.fail(DiagnosticCodes.INVALID_REQUEST, RejectionReason.VALUE_CANNOT_BE_FORMATTED_WITH_THE_REQUESTED_FORMAT)
        } catch (_: DateTimeException) {
            state.pipeRejected(kind, "Expected an ISO date or timestamp with offset matching the requested format")
            Safety.fail(DiagnosticCodes.INVALID_REQUEST, RejectionReason.DATE_MUST_BE_AN_ISO_DATE_OR_AN_ISO)
        }
    }
}

internal class Program(private val nodes: List<Node>, private val depth: Int, private val pipes: TemplatePipes) : CompiledTemplate {
    fun validateDepth(state: Evaluation, scopeDepth: Int) { state.checkDepth(scopeDepth + depth - 1) }
    fun expand(state: Evaluation, scope: Map<String, Any?>, scopeDepth: Int): List<TemplateFragment> {
        validateDepth(state, scopeDepth)
        return state.fragments { state.render(nodes, scope) }
    }
    fun expandContent(state: Evaluation, scope: Map<String, Any?>, scopeDepth: Int): List<ContentTemplateFragment> {
        validateDepth(state, scopeDepth)
        return state.contentFragments { state.render(nodes, scope) }
    }
    override fun render(data: Map<String, *>, options: GenerationOptions, context: GenerationContext): String {
        context.checkActive()
        if (depth > Safety.depth(context)) Safety.limit()
        val state = Evaluation(Safety.snapshot(data, context), options, context, valuePipes = pipes)
        state.render(nodes, emptyMap())
        context.checkActive()
        return state.result()
    }
}

internal class Evaluation(val data: Map<String, Any?>, val options: GenerationOptions, private val context: GenerationContext,
                          private val content: ContentRegistry = ContentRegistry(emptyList()), val valuePipes: TemplatePipes = TemplatePipes.of(emptyList())) {
    val contentNames: Set<String> get() = content.names
    private val values = ValueRegistry(valuePipes)
    private val copies = PipeValues(context, ::step)
    private var pipeCalls = 0L
    fun pipeRejected(name: String, reason: String) = context.logging.operation("PIPE_REJECTED", mapOf("pipe" to name, "reason" to reason, "position" to offset.toString()))
    private var secretTrace = false
    val tracing: Boolean get() = context.logging.enabled && context.logging.mode == dev.nuaforma.contracts.LoggingMode.DEVELOPMENT
    private val secretValues = java.util.IdentityHashMap<Any, Boolean>()
    fun traceRoot(value: Any?) {
        if (!context.logging.enabled || context.logging.mode != dev.nuaforma.contracts.LoggingMode.DEVELOPMENT) return
        if (value != null && secretValues.containsKey(value)) secretTrace = true
    }
    fun tracePath(path: String, value: Any?) {
        if (!context.logging.enabled || context.logging.mode != dev.nuaforma.contracts.LoggingMode.DEVELOPMENT) return
        if (Regex("(?i)(authorization|cookie|token|password|passwd|secret|credential|private.?key|api.?key)").containsMatchIn(path)) secretTrace = true
        if (secretTrace && value != null) secretValues[value] = true
        context.logging.trace("FIELD_RESOLVED", mapOf("position" to offset, "path" to path, "value" to if (secretTrace) "[redacted]" else value))
    }
    fun tracePipe(name: String, value: Any?, arguments: Any?, result: Any?) {
        if (!tracing) return
        context.logging.trace("PIPE_RESULT", mapOf("position" to offset, "pipe" to name,
            "input" to if (secretTrace) "[redacted]" else if (name == "image") "[image payload omitted]" else value,
            "arguments" to if (secretTrace) "[redacted]" else arguments,
            "output" to if (secretTrace) "[redacted]" else result))
    }
    init {
        if (contentNames.any { it in values.names }) Safety.fail(DiagnosticCodes.INVALID_REQUEST, RejectionReason.VALUE_AND_CONTENT_PIPE_NAMES_MUST_NOT_COLLIDE)
    }
    fun pipe(name: String, value: Any?, arguments: List<Any?>): Any? {
        if (++pipeCalls > minOf(context.limits.maxRepetitions, 4096L)) Safety.limit()
        return values.apply(name, value, arguments, this, options, context, copies)
    }
    private var operations = 0
    private var repetitions = 0L
    private var bytes = 0L
    private val output = StringBuilder()
    private var offset = 0
    private var captured: MutableList<TemplateFragment>? = null
    private var capturedContent: MutableList<ContentTemplateFragment>? = null
    private var contentCalls = 0L

    fun checkDepth(depth: Int) { if (depth > Safety.depth(context)) Safety.limit() }
    fun fragments(action: () -> Unit): List<TemplateFragment> {
        val previous = captured
        val result = mutableListOf<TemplateFragment>()
        captured = result
        val start = output.length
        return try { action(); java.util.Collections.unmodifiableList(result) }
        finally { captured = previous; output.setLength(start) }
    }
    fun contentFragments(action: () -> Unit): List<ContentTemplateFragment> {
        val previous = capturedContent
        val result = mutableListOf<ContentTemplateFragment>()
        capturedContent = result
        val start = output.length
        return try { action(); java.util.Collections.unmodifiableList(result) }
        finally { capturedContent = previous; output.setLength(start) }
    }
    fun scopes(value: Any?, scope: Map<String, Any?>, alias: String): List<Map<String, Any?>> = scopeSequence(value, scope, alias).toList()

    private fun scopeSequence(value: Any?, scope: Map<String, Any?>, alias: String): Sequence<Map<String, Any?>> = sequence {
        present(value)
        if (value !is List<*>) typeFailure()
        val secretCollection = secretTrace || secretValues.containsKey(value)
        value.forEachIndexed { index, item ->
            step()
            if (++repetitions > context.limits.maxRepetitions) Safety.limit()
            if (secretCollection && item != null) secretValues[item] = true
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
    fun present(value: Any?) { if (value === Missing) Safety.fail(DiagnosticCodes.MISSING_VALUE, RejectionReason.REQUIRED_TEMPLATE_DATA_IS_MISSING, offset) }
    fun typeFailure(): Nothing = Safety.fail(DiagnosticCodes.INVALID_REQUEST, RejectionReason.TEMPLATE_DATA_HAS_AN_INCOMPATIBLE_TYPE, offset)
    fun boolean(value: Any?): Boolean { present(value); return value as? Boolean ?: typeFailure() }

    fun render(nodes: List<Node>, scope: Map<String, Any?>) {
        for (node in nodes) {
            step()
            secretTrace = false
            when (node) {
                is Node.Text -> append(node.text, node.offset, node.end, true)
                is Node.Value -> {
                    offset = node.offset
                    val value = node.expression.evaluate(this, scope)
                    val formatted = node.format?.let { format ->
                        tracePipe(format.kind, value, listOf(format.pattern), "[pending]")
                        format.apply(value, this).also { tracePipe(format.kind, value, listOf(format.pattern), it) }
                    }
                    append(formatted ?: scalar(value), node.offset, node.end, false)
                }
                is Node.Content -> {
                    offset = node.offset
                    val fragments = capturedContent ?: Safety.fail(DiagnosticCodes.INVALID_TEMPLATE, RejectionReason.CONTENT_PIPES_REQUIRE_A_DOCUMENT_INSERTION_CONTEXT, offset)
                    if (++contentCalls > minOf(context.limits.maxRepetitions, 4096L)) Safety.limit()
                    val value = node.expression.evaluate(this, scope)
                    present(value)
                    val arguments = node.arguments.map { it.evaluate(this, scope).also(::present) }
                    tracePipe(node.name, value, arguments, "[structured content]")
                    val result = content.apply(node.name, value, arguments, options, context, ::step)
                    if (result is ContentResult.Text) charge(result.text)
                    if (fragments.size >= Safety.MAX_NODES) Safety.limit()
                    fragments.add(ContentTemplateFragment(node.offset, node.end, "", false, result))
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
        charge(text)
        capturedContent?.let {
            if (it.size >= Safety.MAX_NODES) Safety.limit()
            it.add(ContentTemplateFragment(start, end, text, literal, null))
        }
        captured?.let {
            if (it.size >= Safety.MAX_NODES) Safety.limit()
            it.add(TemplateFragment(start, end, text, literal))
        }
        output.append(text)
    }
    private fun charge(text: String) {
        val size = Safety.utf8Length(text, context, DiagnosticCodes.INVALID_REQUEST)
        val limit = minOf(context.limits.maxOutputBytes, context.limits.maxExpandedBytes)
        if (size > limit - bytes || text.length.toLong() + output.length > Int.MAX_VALUE - 8L) Safety.limit()
        bytes += size
    }
    fun result(): String = output.toString()
}
