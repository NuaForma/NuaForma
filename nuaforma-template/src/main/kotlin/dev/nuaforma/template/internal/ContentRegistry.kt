package dev.nuaforma.template.internal

import dev.nuaforma.contracts.*
import java.math.BigDecimal

internal class ContentRegistry(pipes: List<ContentPipe>) {
    private class Entry(val pipe: ContentPipe, val definition: ContentPipeDefinition)
    private val entries: Map<String, Entry>
    val names: Set<String> get() = entries.keys
    init {
        if (pipes.size > 128) Safety.limit()
        val registrations = pipes.map { Entry(it, it.definition) }
        if (registrations.map { it.definition.name }.distinct().size != registrations.size) {
            Safety.fail(DiagnosticCodes.INVALID_REQUEST, RejectionReason.CONTENT_PIPE_NAMES_MUST_BE_UNIQUE_WITHIN_THE_SELECTED)
        }
        entries = registrations.associateBy { it.definition.name }
    }

    fun missingAsNull(name: String): Boolean = entries[name]?.definition?.missingAsNull == true

    fun apply(name: String, value: Any?, arguments: List<Any?>, options: GenerationOptions, context: GenerationContext, step: () -> Unit): ContentResult {
        val entry = entries[name] ?: Safety.fail(DiagnosticCodes.INVALID_TEMPLATE, RejectionReason.CONTENT_PIPE_IS_NOT_SUPPORTED_BY_THE_SELECTED_GENERATOR)
        val definitions = entry.definition.arguments
        if (arguments.size !in definitions.count { it.required }..definitions.size) invalid()
        val supplied = definitions.mapIndexed { index, definition ->
            step()
            val argument = if (index < arguments.size) arguments[index] else definition.defaultValue
            if (argument != null && !matches(argument, definition.type)) invalid()
            if (definition.required && argument == null) invalid()
            if (argument != null && definition.allowedValues.isNotEmpty() && definition.allowedValues.none { equivalent(argument, it, step) }) invalid()
            argument
        }
        context.checkActive()
        return try {
            val result: ContentResult? = entry.pipe.apply(freeze(value, step), java.util.Collections.unmodifiableList(supplied.map { freeze(it, step) }),
                ContentPipeContext(options.locale, options.timeZone, context))
            context.checkActive()
            result ?: Safety.fail(DiagnosticCodes.INVALID_REQUEST, RejectionReason.CONTENT_PIPE_RETURNED_NO_STRUCTURED_RESULT)
        } catch (error: GenerationException) {
            context.logging.operation("CONTENT_PIPE_REJECTED", mapOf("pipe" to name, "reason" to error.diagnostics.take(4).joinToString(",") { it.code.id }))
            if (error.diagnostics.any { it.code == DiagnosticCodes.CANCELLED || it.code == DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED }) {
                context.checkActive()
                Safety.fail(error.diagnostics.first { it.code == DiagnosticCodes.CANCELLED || it.code == DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED }.code,
                    RejectionReason.CONTENT_PIPE_PROCESSING_STOPPED_AT_A_RESOURCE_OR_CANCELLATION)
            }
            Safety.fail(DiagnosticCodes.INVALID_REQUEST, RejectionReason.CONTENT_PIPE_COULD_NOT_PROCESS_ITS_VALUE_OR_ARGUMENTS)
        } catch (error: Exception) {
            context.logging.failure("CONTENT_PIPE_FAILED", error, mapOf("pipe" to name))
            if (error is InterruptedException) Thread.currentThread().interrupt()
            context.checkActive()
            Safety.fail(DiagnosticCodes.INVALID_REQUEST, RejectionReason.CONTENT_PIPE_COULD_NOT_PROCESS_ITS_VALUE_OR_ARGUMENTS)
        } catch (error: LinkageError) {
            context.logging.failure("CONTENT_PIPE_FAILED", error, mapOf("pipe" to name))
            Safety.fail(DiagnosticCodes.INVALID_REQUEST, RejectionReason.CONTENT_PIPE_COULD_NOT_PROCESS_ITS_VALUE_OR_ARGUMENTS)
        }
    }

    private fun matches(value: Any, type: OptionType): Boolean = when (type) {
        OptionType.STRING -> value is String
        OptionType.BOOLEAN -> value is Boolean
        OptionType.NUMBER -> value is Number
        OptionType.INTEGER -> type.accepts(value) || value is BigDecimal && value.stripTrailingZeros().scale() <= 0
        OptionType.ARRAY -> value is List<*>
        OptionType.OBJECT -> value is Map<*, *>
    }
    private fun equivalent(left: Any?, right: Any?, step: () -> Unit): Boolean {
        step()
        return when {
            left is Number && right is Number -> BigDecimal(left.toString()).compareTo(BigDecimal(right.toString())) == 0
            left is Map<*, *> && right is Map<*, *> -> left.keys == right.keys && left.all { (key, value) -> equivalent(value, right[key], step) }
            left is List<*> && right is List<*> -> left.size == right.size && left.indices.all { equivalent(left[it], right[it], step) }
            else -> left == right
        }
    }
    private fun freeze(value: Any?, step: () -> Unit): Any? {
        step()
        return when (value) {
            is Map<*, *> -> java.util.Collections.unmodifiableMap(value.entries.associate { it.key to freeze(it.value, step) })
            is List<*> -> java.util.Collections.unmodifiableList(value.map { freeze(it, step) })
            else -> value
        }
    }
    private fun invalid(): Nothing = Safety.fail(DiagnosticCodes.INVALID_REQUEST, RejectionReason.CONTENT_PIPE_ARGUMENTS_DO_NOT_MATCH_THE_DECLARED_CONTRACT)
}
