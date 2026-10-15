package dev.nuaforma.template.internal

import dev.nuaforma.contracts.*
import dev.nuaforma.template.TemplatePipes
import java.math.BigDecimal
import java.math.BigInteger
import java.util.IdentityHashMap

internal class ValueRegistry(private val pipes: TemplatePipes) {
    val names: Set<String> get() = pipes.registrations.keys
    fun apply(name: String, value: Any?, arguments: List<Any?>, state: Evaluation,
              options: GenerationOptions, context: GenerationContext, copies: PipeValues): Any? {
        val implementation = pipes.registrations[name] ?: Safety.invalid(0)
        val definitions = implementation.definition.arguments
        if (arguments.size !in definitions.count { it.required }..definitions.size) invalid()
        val supplied = definitions.mapIndexed { index, definition ->
            val argument = if (index < arguments.size) arguments[index] else definition.defaultValue
            if (argument != null && !matches(argument, definition.type)) invalid()
            if (definition.required && argument == null) invalid()
            if (argument != null && definition.allowedValues.isNotEmpty() && definition.allowedValues.none { equivalent(argument, it, state) }) invalid()
            copies.copy(argument)
        }
        val input = copies.copy(value)
        context.checkActive()
        return try {
            val result = implementation.apply(input, java.util.Collections.unmodifiableList(supplied), TemplatePipeContext(options.locale, options.timeZone, context))
            context.checkActive()
            copies.copy(result)
        } catch (error: GenerationException) {
            context.checkActive()
            val boundary = error.diagnostics.firstOrNull { it.code == DiagnosticCodes.CANCELLED || it.code == DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED }
            if (boundary != null) Safety.fail(boundary.code, "Value pipe processing stopped at a resource or cancellation boundary")
            invalid()
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            context.checkActive()
            invalid()
        } catch (_: LinkageError) { invalid() }
    }
    private fun matches(value: Any, type: OptionType): Boolean = when (type) {
        OptionType.INTEGER -> type.accepts(value) || value is BigDecimal && value.stripTrailingZeros().scale() <= 0
        else -> type.accepts(value)
    }
    private fun equivalent(left: Any?, right: Any?, state: Evaluation): Boolean {
        state.step()
        return when {
            left is Number && right is Number -> BigDecimal(left.toString()).compareTo(BigDecimal(right.toString())) == 0
            left is Map<*, *> && right is Map<*, *> -> left.keys == right.keys && left.all { (key, value) -> equivalent(value, right[key], state) }
            left is List<*> && right is List<*> -> left.size == right.size && left.indices.all { equivalent(left[it], right[it], state) }
            else -> left == right
        }
    }
    private fun invalid(): Nothing = Safety.fail(DiagnosticCodes.INVALID_REQUEST, "Value pipe could not process its value, arguments or result")
}

/** Shared across a generation and its structural scopes, including every intermediate result. */
internal class PipeValues(private val context: GenerationContext, private val step: () -> Unit) {
    private var nodes = 0
    private var bytes = 0L
    fun copy(value: Any?): Any? = copy(value, 0, IdentityHashMap())
    private fun text(value: String) {
        val size = Safety.utf8Length(value, context, DiagnosticCodes.INVALID_REQUEST)
        if (size > context.limits.maxExpandedBytes - bytes) Safety.limit()
        bytes += size
    }
    private fun copy(value: Any?, depth: Int, ancestors: IdentityHashMap<Any, Boolean>): Any? {
        step()
        if (++nodes > Safety.MAX_NODES || depth > minOf(64, Safety.depth(context))) Safety.limit()
        return when (value) {
            null, is Boolean -> value
            is String -> value.also(::text)
            is Byte, is Short, is Int, is Long, is BigInteger, is BigDecimal, is Float, is Double -> {
                val number = try { when (value) {
                    is BigDecimal -> value
                    is BigInteger -> { if (value.bitLength() > 3322) Safety.limit(); BigDecimal(value) }
                    else -> BigDecimal(value.toString())
                } } catch (_: NumberFormatException) { invalid() }
                if (number.unscaledValue().bitLength() > 3322 || number.precision() > 1000 || number.scale().toLong() !in -1000L..1000L) Safety.limit()
                number
            }
            is Map<*, *>, is List<*> -> {
                if (ancestors.put(value, true) != null) invalid()
                try {
                    if (value is Map<*, *>) {
                        val result = linkedMapOf<String, Any?>()
                        for ((key, item) in value) {
                            step()
                            if (key !is String) invalid()
                            text(key)
                            result[key] = copy(item, depth + 1, ancestors)
                        }
                        java.util.Collections.unmodifiableMap(result)
                    } else java.util.Collections.unmodifiableList((value as List<*>).map { copy(it, depth + 1, ancestors) })
                } finally { ancestors.remove(value) }
            }
            else -> invalid()
        }
    }
    private fun invalid(): Nothing = Safety.fail(DiagnosticCodes.INVALID_REQUEST, "Value pipes require finite JSON-compatible values without cycles")
}
