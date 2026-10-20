package dev.nuaforma.server.internal

import dev.nuaforma.contracts.*

internal class ApiRequest(
    val requirements: GenerationRequirements,
    val data: Map<String, Any?>,
    val providerId: String?,
    val filename: String,
)

internal class ApiFailure(val status: Int, val code: String, val safeMessage: String) : Exception(safeMessage)

internal interface JsonCodec {
    fun decode(source: ByteArray): ApiRequest
    fun encode(value: Any?): String
}

internal object ApiErrors {
    fun status(diagnostics: List<Diagnostic>): Int {
        val codes = diagnostics.map { it.code.id }.toSet()
        return when {
            "CANCELLED" in codes -> 408
            diagnostics.any { it.code.id == "RESOURCE_LIMIT_EXCEEDED" && it.location == "deadline" } -> 408
            "RESOURCE_LIMIT_EXCEEDED" in codes -> 413
            codes.any { it in setOf("INVALID_PROVIDER", "INVALID_PROVIDER_RESULT", "GENERATION_FAILED") } -> 500
            "PROVIDER_UNAVAILABLE" in codes -> 503
            else -> 422
        }
    }

    // Provider diagnostics may originate in separately installed executable extensions.
    fun diagnostics(values: List<Diagnostic>, mode: LoggingMode = LoggingMode.PRODUCTION): List<Map<String, Any?>> = values.take(64).map {
        val details = explanation(it)
        mapOf("code" to it.code.id, "severity" to it.severity.name,
            "message" to if (mode == LoggingMode.DEVELOPMENT && details.isNotEmpty()) it.rejectionReason!!.message else when (it.code.id) {
                "PROVIDER_UNAVAILABLE" -> "Requested provider or conversion backend is unavailable; check capabilities and trusted runtime configuration"
                "AMBIGUOUS_PROVIDER" -> "Multiple providers match; supply an explicit providerId"
                "RESOURCE_LIMIT_EXCEEDED" -> "Document processing exceeded its resource limits"
                else -> "Document processing diagnostic: ${it.code.id}"
            }) + if (mode == LoggingMode.DEVELOPMENT) details else emptyMap()
    }

    /** Only closed application-owned text and constrained coordinates may cross this boundary. */
    fun explanation(diagnostic: Diagnostic): Map<String, String> {
        val reason = diagnostic.rejectionReason ?: return emptyMap()
        val details = linkedMapOf("reason" to reason.name, "remediation" to reason.remediation)
        reason.construct?.let { details["construct"] = it }
        diagnostic.part?.let { details["part"] = if (PART.matches(it)) it else "[other package part]" }
        diagnostic.location?.takeIf { LOCATION.matches(it) }?.let { details["location"] = it }
        return details
    }

    fun logRejections(logging: GenerationLogging, values: List<Diagnostic>) {
        values.take(32).forEach { diagnostic ->
            val fields = linkedMapOf("code" to diagnostic.code.id)
            fields.putAll(explanation(diagnostic))
            diagnostic.rejectionReason?.let { fields["message"] = it.message }
            logging.operation("GENERATION_REJECTED", fields)
        }
    }

    private val LOCATION = Regex("(?:template:[0-9]{1,8}|xml:[0-9]{1,8}:[0-9]{1,8})")
    private val PART = Regex("(?:\\[Content_Types\\]\\.xml|_rels/\\.rels|word/(?:document|styles|settings|fontTable|numbering|footnotes|endnotes|comments|(?:header|footer)[0-9]{1,4})\\.xml|word/_rels/(?:document|settings|(?:header|footer)[0-9]{1,4})\\.xml\\.rels)")
}
