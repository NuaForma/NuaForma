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
    fun diagnostics(values: List<Diagnostic>): List<Map<String, Any?>> = values.take(64).map {
        mapOf("code" to it.code.id, "severity" to it.severity.name,
            "message" to when (it.code.id) {
                "PROVIDER_UNAVAILABLE" -> "Requested provider or conversion backend is unavailable; check capabilities and trusted runtime configuration"
                "AMBIGUOUS_PROVIDER" -> "Multiple providers match; supply an explicit providerId"
                "RESOURCE_LIMIT_EXCEEDED" -> "Document processing exceeded its resource limits"
                else -> "Document processing diagnostic: ${it.code.id}"
            })
    }
}
