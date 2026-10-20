package dev.nuaforma.docx.pdf.internal

import dev.nuaforma.contracts.*

internal class PdfOptions private constructor(
    val docxOptions: Map<String, Any?>,
    val paragraphDefaults: ParagraphDefaults?,
    val profile: PdfProfile,
) {
    data class ParagraphDefaults(val afterTwips: Int?, val line240ths: Int?)

    companion object {
        private val keys = setOf("version", "profile", "fontMode", "fonts", "paragraphDefaults")
        fun read(values: Map<String, Any?>): PdfOptions {
            if (values.keys.any { it !in keys }) invalid(RejectionReason.UNRECOGNIZED_PDF_OPTIONS)
            val profile = if (!values.containsKey("profile")) PdfProfile.PDFA_3B else
                PdfProfile.entries.firstOrNull { it.id == values["profile"] } ?: invalid(RejectionReason.UNSUPPORTED_PDF_PROFILE)
            if (values.containsKey("version") && values["version"] != profile.version) invalid(RejectionReason.PDF_VERSION_CONFLICTS_WITH_THE_REQUESTED_PROFILE)
            val defaults = if (!values.containsKey("paragraphDefaults")) null else {
                val raw = values["paragraphDefaults"] as? Map<*, *> ?: invalid(RejectionReason.PARAGRAPHDEFAULTS_MUST_BE_AN_OBJECT)
                if (raw.isEmpty() || raw.keys.any { it !in setOf("afterTwips", "line240ths") }) invalid(RejectionReason.INVALID_PARAGRAPHDEFAULTS_FIELDS)
                ParagraphDefaults(integer(raw, "afterTwips", 0..2880), integer(raw, "line240ths", 1..2400))
            }
            return PdfOptions(values.filterKeys { it in setOf("fontMode", "fonts") }, defaults, profile)
        }

        private fun integer(values: Map<*, *>, name: String, range: IntRange): Int? {
            if (!values.containsKey(name)) return null
            val value = values[name]
            if (value !is Byte && value !is Short && value !is Int && value !is Long && value !is java.math.BigInteger) invalid(RejectionReason.PARAGRAPH_DEFAULTS_REQUIRE_BOUNDED_INTEGERS)
            val number = value.toString().toLongOrNull() ?: invalid(RejectionReason.PARAGRAPH_DEFAULTS_REQUIRE_BOUNDED_INTEGERS)
            if (number < range.first || number > range.last) invalid(RejectionReason.PARAGRAPH_DEFAULT_IS_OUTSIDE_ITS_SUPPORTED_RANGE)
            return number.toInt()
        }

        private fun invalid(reason: RejectionReason): Nothing = PdfFailure.fail(DiagnosticCodes.UNSUPPORTED_OPTION, reason)
    }
}

internal enum class PdfProfile(val id: String, val version: String) {
    STANDARD("standard", "1.7"), PDFA_1B("pdfa-1b", "1.4"), PDFA_2B("pdfa-2b", "1.7"), PDFA_3B("pdfa-3b", "1.7"),
}
