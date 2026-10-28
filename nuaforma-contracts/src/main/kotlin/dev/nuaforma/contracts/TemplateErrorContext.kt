package dev.nuaforma.contracts

/** Sensitive development-only excerpt. Positions count UTF-16 units from zero, including end-of-input. */
class TemplateErrorContext @JvmOverloads constructor(source: String, position: Int, val sourceOffset: Int = 0) {
    val redacted: Boolean = SECRET_FIELD.containsMatchIn(source)
    val excerptStart: Int = (position.coerceIn(0, source.length) - 120).coerceAtLeast(0)
    val expression: String = if (redacted) "[redacted expression]" else source.substring(excerptStart, minOf(source.length, excerptStart + 256))
        .map { if (it.code < 32 || it.code in 127..159 || Character.getType(it) == Character.FORMAT.toInt()) ' ' else it }.joinToString("")
    val position: Int = if (redacted) 0 else position.coerceIn(0, source.length) - excerptStart

    init { require(sourceOffset >= 0) { "Source offset must not be negative" } }

    private companion object {
        val SECRET_FIELD = Regex("(?i)(authorization|cookie|token|password|passwd|secret|credential|private.?key|api.?key)")
    }
}
