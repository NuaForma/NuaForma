package dev.nuaforma.core

import dev.nuaforma.contracts.*

internal object ProviderLogs {
    fun availability(logging: GenerationLogging, id: String, declared: ProviderCapabilities, available: ProviderAvailability) {
        val state = when {
            available.readFormats.isEmpty() || available.writeFormats.isEmpty() -> "unavailable"
            available.readFormats != declared.readFormats || available.writeFormats != declared.writeFormats -> "partial"
            else -> "available"
        }
        logging.operation("PROVIDER_READINESS", mapOf("provider" to id, "state" to state,
            "declaredRead" to declared.readFormats.map { it.id }.sorted().joinToString(","),
            "declaredWrite" to declared.writeFormats.map { it.id }.sorted().joinToString(","),
            "usableRead" to available.readFormats.map { it.id }.sorted().joinToString(","),
            "usableWrite" to available.writeFormats.map { it.id }.sorted().joinToString(",")))
        available.diagnostics.forEach { diagnostic(logging, it, id) }
    }
    fun diagnostic(logging: GenerationLogging, diagnostic: Diagnostic, providerId: String? = null) {
        val reason = when (diagnostic.code.id) {
            "PROVIDER_UNAVAILABLE" -> "Check the configured renderer, validator, executable permissions and isolation readiness; independent formats may remain usable"
            "INCOMPATIBLE_CONTRACT" -> "Install a provider with the same contract major and a supported minimum minor"
            "DUPLICATE_PROVIDER" -> "Remove duplicate provider identifiers or implementation registrations"
            "INVALID_PROVIDER" -> "Check provider initialization, runtime dependencies and descriptor identity"
            else -> "Check the provider registration descriptor and declared capability requirements"
        }
        val identity = providerId ?: diagnostic.location?.takeIf { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) }
        logging.operation("PROVIDER_DIAGNOSTIC", mapOf("provider" to (identity ?: "unidentified-registration"), "diagnostic" to diagnostic.code.id, "reason" to reason))
    }
}
