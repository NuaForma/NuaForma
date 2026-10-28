package dev.nuaforma.template.internal

import dev.nuaforma.contracts.RejectionReason
import dev.nuaforma.contracts.Diagnostic
import dev.nuaforma.contracts.DiagnosticCode
import dev.nuaforma.contracts.DiagnosticCodes
import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.contracts.GenerationException
import dev.nuaforma.contracts.LoggingMode
import dev.nuaforma.contracts.TemplateErrorContext
import java.math.BigDecimal
import java.math.BigInteger
import java.util.IdentityHashMap

internal object Safety {
    const val MAX_NODES = 100_000
    const val MAX_OPERATIONS = 1_000_000
    fun depth(context: GenerationContext) = minOf(context.limits.maxExpressionDepth, 128)

    fun fail(code: DiagnosticCode, reason: RejectionReason, offset: Int? = null): Nothing =
        throw GenerationException(listOf(Diagnostic(code, reason, location = offset?.let { "template:$it" })))

    fun invalid(offset: Int): Nothing = fail(DiagnosticCodes.INVALID_TEMPLATE, RejectionReason.INVALID_TEMPLATE_SYNTAX_OR_UNSUPPORTED_OPERATION, offset)
    fun syntax(offset: Int, reason: RejectionReason): Nothing = fail(DiagnosticCodes.INVALID_TEMPLATE, reason, offset)

    fun enrich(error: GenerationException, source: String, context: GenerationContext, sourceOffset: Int = 0): GenerationException {
        if (context.logging.mode != LoggingMode.DEVELOPMENT) return error
        return GenerationException(error.diagnostics.map { diagnostic ->
            val offset = diagnostic.location?.removePrefix("template:")?.toIntOrNull()
            if (diagnostic.code != DiagnosticCodes.INVALID_TEMPLATE || diagnostic.templateContext != null || offset == null) diagnostic
            else diagnostic.withTemplateContext(TemplateErrorContext(source, offset - sourceOffset, sourceOffset))
        })
    }

    fun <T> inSource(source: String, context: GenerationContext, action: () -> T): T = try { action() }
        catch (error: GenerationException) { throw enrich(error, source, context) }
    fun limit(): Nothing = fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, RejectionReason.TEMPLATE_PROCESSING_BUDGET_WAS_EXCEEDED)

    fun utf8Length(value: String, context: GenerationContext, code: DiagnosticCode): Long {
        var bytes = 0L
        var index = 0
        while (index < value.length) {
            if (index % 1024 == 0) context.checkActive()
            val character = value[index++]
            bytes += when {
                character.code < 128 -> 1
                character.code < 2048 -> 2
                character.isHighSurrogate() -> {
                    if (index >= value.length || !value[index++].isLowSurrogate()) fail(code, RejectionReason.TEXT_CONTAINS_AN_INVALID_UNICODE_SEQUENCE)
                    4
                }
                character.isLowSurrogate() -> fail(code, RejectionReason.TEXT_CONTAINS_AN_INVALID_UNICODE_SEQUENCE)
                else -> 3
            }
        }
        return bytes
    }

    fun snapshot(data: Map<String, *>, context: GenerationContext): Map<String, Any?> {
        var count = 0
        var textBytes = 0L
        val ancestors = IdentityHashMap<Any, Boolean>()
        fun copy(value: Any?, depth: Int): Any? {
            context.checkActive()
            if (++count > MAX_NODES || depth > 64) limit()
            return when (value) {
                null, is Boolean -> value
                is String -> {
                    textBytes += utf8Length(value, context, DiagnosticCodes.INVALID_REQUEST)
                    if (textBytes > context.limits.maxExpandedBytes) limit()
                    value
                }
                is Byte, is Short, is Int, is Long, is BigInteger, is BigDecimal, is Float, is Double -> {
                    val number = try { when (value) {
                        is BigDecimal -> value
                        is BigInteger -> {
                            if (value.bitLength() > 3322) limit()
                            BigDecimal(value)
                        }
                        else -> BigDecimal(value.toString())
                    } } catch (_: NumberFormatException) {
                        fail(DiagnosticCodes.INVALID_REQUEST, RejectionReason.DATA_NUMBERS_MUST_BE_FINITE)
                    }
                    if (number.unscaledValue().bitLength() > 3322 || number.precision() > 1000 || number.scale().toLong() !in -1000L..1000L) limit()
                    number
                }
                is Map<*, *>, is List<*> -> {
                    if (ancestors.put(value, true) != null) fail(DiagnosticCodes.INVALID_REQUEST, RejectionReason.DATA_MUST_NOT_CONTAIN_CYCLES)
                    try {
                        if (value is Map<*, *>) {
                            val result = linkedMapOf<String, Any?>()
                            value.forEach { (key, item) ->
                                if (key !is String) fail(DiagnosticCodes.INVALID_REQUEST, RejectionReason.DATA_OBJECT_KEYS_MUST_BE_STRINGS)
                                textBytes += utf8Length(key, context, DiagnosticCodes.INVALID_REQUEST)
                                if (textBytes > context.limits.maxExpandedBytes) limit()
                                result[key] = copy(item, depth + 1)
                            }
                            result
                        } else (value as List<*>).map { copy(it, depth + 1) }
                    } finally { ancestors.remove(value) }
                }
                else -> fail(DiagnosticCodes.INVALID_REQUEST, RejectionReason.DATA_MUST_CONTAIN_JSON_COMPATIBLE_VALUES_NOT_APPLICATION_OBJECTS)
            }
        }
        @Suppress("UNCHECKED_CAST")
        return copy(data, 0) as Map<String, Any?>
    }
}
