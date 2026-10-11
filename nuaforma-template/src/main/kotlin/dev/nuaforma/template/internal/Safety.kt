package dev.nuaforma.template.internal

import dev.nuaforma.contracts.Diagnostic
import dev.nuaforma.contracts.DiagnosticCode
import dev.nuaforma.contracts.DiagnosticCodes
import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.contracts.GenerationException
import java.math.BigDecimal
import java.math.BigInteger
import java.util.IdentityHashMap

internal object Safety {
    const val MAX_NODES = 100_000
    const val MAX_OPERATIONS = 1_000_000
    fun depth(context: GenerationContext) = minOf(context.limits.maxExpressionDepth, 128)

    fun fail(code: DiagnosticCode, message: String, offset: Int? = null): Nothing =
        throw GenerationException(listOf(Diagnostic(code, message, location = offset?.let { "template:$it" })))

    fun invalid(offset: Int): Nothing = fail(DiagnosticCodes.INVALID_TEMPLATE, "Invalid template syntax or unsupported operation", offset)
    fun limit(): Nothing = fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, "Template processing budget was exceeded")

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
                    if (index >= value.length || !value[index++].isLowSurrogate()) fail(code, "Text contains an invalid Unicode sequence")
                    4
                }
                character.isLowSurrogate() -> fail(code, "Text contains an invalid Unicode sequence")
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
                        fail(DiagnosticCodes.INVALID_REQUEST, "Data numbers must be finite")
                    }
                    if (number.unscaledValue().bitLength() > 3322 || number.precision() > 1000 || number.scale().toLong() !in -1000L..1000L) limit()
                    number
                }
                is Map<*, *>, is List<*> -> {
                    if (ancestors.put(value, true) != null) fail(DiagnosticCodes.INVALID_REQUEST, "Data must not contain cycles")
                    try {
                        if (value is Map<*, *>) {
                            val result = linkedMapOf<String, Any?>()
                            value.forEach { (key, item) ->
                                if (key !is String) fail(DiagnosticCodes.INVALID_REQUEST, "Data object keys must be strings")
                                textBytes += utf8Length(key, context, DiagnosticCodes.INVALID_REQUEST)
                                if (textBytes > context.limits.maxExpandedBytes) limit()
                                result[key] = copy(item, depth + 1)
                            }
                            result
                        } else (value as List<*>).map { copy(it, depth + 1) }
                    } finally { ancestors.remove(value) }
                }
                else -> fail(DiagnosticCodes.INVALID_REQUEST, "Data must contain JSON-compatible values, not application objects")
            }
        }
        @Suppress("UNCHECKED_CAST")
        return copy(data, 0) as Map<String, Any?>
    }
}
