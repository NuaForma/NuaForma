package dev.nuaforma.contracts

import java.util.IdentityHashMap

enum class LoggingMode { PRODUCTION, DEVELOPMENT }
enum class GenerationLogLevel { DEBUG, INFO, WARNING, ERROR }

/** Already bounded and escaped; no Throwable or caller-owned value escapes to the backend. */
class GenerationLogEvent internal constructor(val code: String, val requestId: String?, val development: Boolean,
    val level: GenerationLogLevel, val text: String)

fun interface GenerationLogSink {
    fun write(event: GenerationLogEvent)
}

/** Explicit, request-private diagnostics. No global logger, environment access or implicit data access. */
class GenerationLogging @JvmOverloads constructor(
    val mode: LoggingMode = LoggingMode.PRODUCTION,
    val requestId: String? = null,
    private val sink: GenerationLogSink = NOOP,
) {
    init { require(requestId == null || requestId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))) { "Invalid logging request identity" } }
    val enabled: Boolean get() = sink !== NOOP
    private var events = 0
    private var bytes = 0
    private var operationalEvents = 0
    private var operationalBytes = 0
    private var failureEvents = 0
    private var failureBytes = 0

    /** Only application-owned codes and safe operational labels belong here, never document values. */
    fun operation(code: String, fields: Map<String, String> = emptyMap()) = emit(code, false, fields, null)

    /** Exception messages and suppressed text are intentionally never printed, even in development. */
    fun failure(code: String, error: Throwable) = emit(code, false, emptyMap<String, Any?>(), error)
    fun failure(code: String, error: Throwable, fields: Map<String, String>) = emit(code, false, fields, error)

    fun trace(code: String, fields: Map<String, *>) {
        if (mode == LoggingMode.DEVELOPMENT) emit(code, true, fields, null)
    }

    @Synchronized
    private fun emit(code: String, sensitive: Boolean, fields: Map<String, *>, error: Throwable?) {
        if (sink === NOOP || !code.matches(Regex("[A-Z][A-Z0-9_]{0,63}"))) return
        val operationLimit = if (requestId == null) 4096 else 64
        val operationBytes = if (requestId == null) 1048576 else 65536
        if (error != null && (failureEvents >= 16 || failureBytes >= 65536) ||
            error == null && sensitive && (events >= 256 || bytes >= 131072) ||
            error == null && !sensitive && (operationalEvents >= operationLimit || operationalBytes >= operationBytes)) return
        val text = StringBuilder()
        val seen = IdentityHashMap<Any, Boolean>()
        var nodes = 0
        fun escaped(value: String, limit: Int = 256) {
            var count = 0
            for (character in value) {
                if (++count > limit || text.length >= 1800) { text.append("…"); break }
                when {
                    character == '\\' -> text.append("\\\\")
                    character == '"' -> text.append("\\\"")
                    character.code < 32 || character.code in 127..159 || character.isSurrogate() || Character.getType(character) == Character.FORMAT.toInt() || character == '\u2028' || character == '\u2029' -> text.append("\\u").append(character.code.toString(16).padStart(4, '0'))
                    else -> text.append(character)
                }
            }
        }
        fun secret(key: String) = SECRET.containsMatchIn(key)
        fun render(value: Any?, depth: Int) {
            if (++nodes > 48 || depth > 4 || text.length >= 1800) { text.append("[bounded]"); return }
            when (value) {
                null -> text.append("null")
                is String -> {
                    if (value.startsWith("Bearer ", true) || value.startsWith("Basic ", true) || PRIVATE_KEY.containsMatchIn(value.take(80))) text.append("[redacted]")
                    else if (value.length >= 128 && value.take(128).all { it.isLetterOrDigit() || it in "+/=_-" } || value.startsWith("data:image/", true) ||
                        value.startsWith("iVBORw0KGgo") || value.startsWith("/9j/")) text.append("[payload omitted]")
                    else { text.append('"'); escaped(value); text.append('"') }
                }
                is Boolean, is Byte, is Short, is Int, is Long, is Float, is Double -> text.append(value)
                is java.math.BigDecimal -> if (value.javaClass == java.math.BigDecimal::class.java && value.precision() <= 1000 && value.scale() in -1000..1000) escaped(value.toEngineeringString(), 128) else text.append("[number]")
                is java.math.BigInteger -> if (value.javaClass == java.math.BigInteger::class.java && value.bitLength() <= 3322) escaped(value.toString(), 128) else text.append("[number]")
                is Map<*, *> -> {
                    if (!container(value)) { text.append("[opaque]"); return }
                    if (seen.put(value, true) != null) { text.append("[cycle]"); return }
                    text.append('{')
                    for ((key, item) in value.entries.take(8)) {
                        if (key !is String) continue
                        escaped(key, 64); text.append('=')
                        if (secret(key)) text.append("[redacted]") else render(item, depth + 1)
                        text.append(';')
                    }
                    text.append('}'); seen.remove(value)
                }
                is List<*> -> {
                    if (!container(value)) { text.append("[opaque]"); return }
                    if (seen.put(value, true) != null) { text.append("[cycle]"); return }
                    text.append('['); for (item in value.take(8)) { render(item, depth + 1); text.append(';') }; text.append(']'); seen.remove(value)
                }
                else -> text.append("[opaque]")
            }
        }
        try {
            for ((key, value) in if (container(fields)) fields.entries.take(12) else emptyList()) {
                if (text.length >= 1800) break
                escaped(key, 64); text.append('=')
                if (secret(key)) text.append("[redacted]") else render(value, 0)
                text.append(' ')
            }
            var cause = error
            val causes = IdentityHashMap<Throwable, Boolean>()
            repeat(4) {
                val current = cause ?: return@repeat
                if (causes.put(current, true) != null) return@repeat
                text.append(" exception="); escaped(current.javaClass.name, 160)
                for (frame in current.stackTrace.take(12)) {
                    if (text.length >= 1800) break
                    text.append(" at "); escaped(frame.className, 100); text.append('.'); escaped(frame.methodName, 80); text.append(':').append(frame.lineNumber)
                }
                cause = current.cause
            }
            val encoded = text.toString().take(2000).toByteArray(Charsets.UTF_8)
            val bounded = if (encoded.size <= 3584) String(encoded, Charsets.UTF_8) else {
                var end = 3584
                while (end > 0 && encoded[end].toInt() and 0xc0 == 0x80) end--
                String(encoded, 0, end, Charsets.UTF_8)
            }
            val length = bounded.toByteArray(Charsets.UTF_8).size
            if (error != null) {
                if (failureBytes + length > 65536) return
                failureEvents++; failureBytes += length
            } else if (sensitive) {
                if (bytes + length > 131072) return
                events++; bytes += length
            } else {
                if (operationalBytes + length > operationBytes) return
                operationalEvents++; operationalBytes += length
            }
            val level = when {
                error != null -> GenerationLogLevel.ERROR
                sensitive -> GenerationLogLevel.DEBUG
                code.endsWith("UNAVAILABLE") || code.endsWith("REJECTED") || code.endsWith("FAILED") || code == "PROVIDER_DIAGNOSTIC" -> GenerationLogLevel.WARNING
                else -> GenerationLogLevel.INFO
            }
            sink.write(GenerationLogEvent(code, requestId, sensitive, level, bounded))
        } catch (_: Exception) { /* Logging must not replace the generation outcome. */ }
    }

    companion object {
        private val NOOP = GenerationLogSink { }
        private val SECRET = Regex("(?i)(authorization|cookie|token|password|passwd|secret|credential|private.?key|api.?key)")
        private val PRIVATE_KEY = Regex("^-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----")
        @JvmField val NONE = GenerationLogging()
        private fun container(value: Any): Boolean = value.javaClass.name in setOf("java.util.LinkedHashMap", "java.util.HashMap", "java.util.ArrayList",
            "java.util.Arrays\$ArrayList", "java.util.Collections\$SingletonMap", "java.util.Collections\$SingletonList", "java.util.Collections\$UnmodifiableMap",
            "java.util.Collections\$UnmodifiableRandomAccessList", "java.util.ImmutableCollections\$Map1", "java.util.ImmutableCollections\$MapN",
            "java.util.ImmutableCollections\$List12", "java.util.ImmutableCollections\$ListN", "kotlin.collections.EmptyMap", "kotlin.collections.EmptyList")
    }
}
