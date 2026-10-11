package dev.nuaforma.contracts

/** Extensible, canonical identifier; not a file extension or class name to load. */
data class DocumentFormat(val id: String) {
    init {
        require(ID.matches(id)) { "Format identifiers must be lowercase tokens" }
    }

    companion object {
        private val ID = Regex("[a-z][a-z0-9.+-]{0,127}")
        @JvmField val DOCX = DocumentFormat("docx")
        @JvmField val PDF = DocumentFormat("pdf")
    }
}

/** Provider-interpreted options, including versions and profiles, are literal structured values. */
class FormatRequest @JvmOverloads constructor(
    val format: DocumentFormat,
    options: Map<String, *> = emptyMap<String, Any?>(),
) {
    val options: Map<String, Any?> = StructuredValues.objectCopy(options)
}

enum class OptionType {
    STRING, INTEGER, NUMBER, BOOLEAN, OBJECT, ARRAY;

    fun accepts(value: Any?): Boolean = when (this) {
        STRING -> value is String
        INTEGER -> value is Byte || value is Short || value is Int || value is Long || value is java.math.BigInteger
        NUMBER -> value is Number
        BOOLEAN -> value is Boolean
        OBJECT -> value is Map<*, *>
        ARRAY -> value is List<*>
    }
}

/** Descriptive metadata; providers also validate cross-option combinations in assess(). */
class OptionDefinition @JvmOverloads constructor(
    val name: String,
    val type: OptionType,
    val description: String,
    val required: Boolean = false,
    defaultValue: Any? = null,
    allowedValues: List<Any?> = emptyList(),
) {
    val defaultValue: Any? = StructuredValues.valueCopy(defaultValue)
    val allowedValues: List<Any?> = run {
        @Suppress("UNCHECKED_CAST")
        StructuredValues.valueCopy(allowedValues) as List<Any?>
    }

    init {
        require(name.isNotBlank() && description.isNotBlank()) { "Option name and description are required" }
        require(!required || defaultValue == null) { "A required option must not have a default" }
        require(defaultValue == null || type.accepts(this.defaultValue)) { "Default has an incompatible type" }
        require(this.allowedValues.all { type.accepts(it) }) { "Allowed values have incompatible types" }
        require(defaultValue == null || this.allowedValues.isEmpty() || this.defaultValue in this.allowedValues) {
            "Default is not an allowed value"
        }
    }
}
