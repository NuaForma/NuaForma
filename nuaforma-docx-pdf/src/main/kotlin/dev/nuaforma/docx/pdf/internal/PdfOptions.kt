package dev.nuaforma.docx.pdf.internal

import dev.nuaforma.contracts.*

internal class PdfOptions private constructor(
    val docxOptions: Map<String, Any?>,
    val paragraphDefaults: ParagraphDefaults?,
) {
    data class ParagraphDefaults(val afterTwips: Int?, val line240ths: Int?)

    companion object {
        private val keys = setOf("version", "profile", "fontMode", "fonts", "paragraphDefaults")
        fun read(values: Map<String, Any?>): PdfOptions {
            if (values.keys.any { it !in keys }) invalid("Unrecognized PDF options")
            if (values.containsKey("version") && values["version"] != "1.7") invalid("Only PDF version 1.7 is supported")
            if (values.containsKey("profile") && values["profile"] != "standard") invalid("Only the standard PDF profile is supported")
            val defaults = if (!values.containsKey("paragraphDefaults")) null else {
                val raw = values["paragraphDefaults"] as? Map<*, *> ?: invalid("paragraphDefaults must be an object")
                if (raw.isEmpty() || raw.keys.any { it !in setOf("afterTwips", "line240ths") }) invalid("Invalid paragraphDefaults fields")
                ParagraphDefaults(integer(raw, "afterTwips", 0..2880), integer(raw, "line240ths", 1..2400))
            }
            return PdfOptions(values.filterKeys { it in setOf("fontMode", "fonts") }, defaults)
        }

        private fun integer(values: Map<*, *>, name: String, range: IntRange): Int? {
            if (!values.containsKey(name)) return null
            val value = values[name]
            if (value !is Byte && value !is Short && value !is Int && value !is Long && value !is java.math.BigInteger) invalid("Paragraph defaults require bounded integers")
            val number = value.toString().toLongOrNull() ?: invalid("Paragraph defaults require bounded integers")
            if (number < range.first || number > range.last) invalid("Paragraph default is outside its supported range")
            return number.toInt()
        }

        private fun invalid(message: String): Nothing = PdfFailure.fail(DiagnosticCodes.UNSUPPORTED_OPTION, message)
    }
}
