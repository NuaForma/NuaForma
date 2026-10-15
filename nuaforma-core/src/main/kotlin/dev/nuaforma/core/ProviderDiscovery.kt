package dev.nuaforma.core

import dev.nuaforma.contracts.*
import java.util.Collections
import java.util.ServiceConfigurationError

internal class ProviderDiscovery(private val availableVersion: ContractVersion, private val logging: GenerationLogging = GenerationLogging.NONE) {
    fun load(registrations: List<ProviderRegistration>, initialDiagnostics: List<Diagnostic> = emptyList()): ProviderRegistry {
        val diagnostics = initialDiagnostics.toMutableList()
        val duplicateIds = registrations.groupBy { it.metadata.id }.filterValues { it.size > 1 }.keys
        val duplicateClasses = registrations.groupBy { it.metadata.implementationClass }.filterValues { it.size > 1 }.keys
        val providers = mutableListOf<RegisteredProvider>()
        for (registration in registrations.sortedBy { it.metadata.id }) {
            val metadata = registration.metadata
            if (metadata.id in duplicateIds || metadata.implementationClass in duplicateClasses) {
                diagnostics += failure("DUPLICATE_PROVIDER", "Duplicate provider identity", metadata.id)
                continue
            }
            if (!availableVersion.accepts(metadata.requiredContract)) {
                logging.operation("PROVIDER_CONTRACT_REJECTED", mapOf("provider" to metadata.id,
                    "requiredContract" to metadata.requiredContract.toString(), "runtimeContract" to availableVersion.toString()))
                diagnostics += failure("INCOMPATIBLE_CONTRACT", "Provider requires contract ${metadata.requiredContract}; available contract is $availableVersion", metadata.id)
                continue
            }
            try {
                val provider = registration.factory.get()
                require(provider.javaClass.name == metadata.implementationClass)
                val capabilities = provider.capabilities()
                val availability = if (provider is ProviderReadiness) provider.availability(GenerationContext(logging)) else provider.availability()
                availability.validateAgainst(capabilities)
                ProviderLogs.availability(logging, metadata.id, capabilities, availability)
                providers += RegisteredProvider(metadata, capabilities, provider)
                logging.operation("PROVIDER_ACTIVATED", mapOf("provider" to metadata.id,
                    "releaseVersion" to metadata.releaseVersion, "requiredContract" to metadata.requiredContract.toString()))
            } catch (error: Exception) {
                logging.failure("PROVIDER_INITIALIZATION_FAILED", error, mapOf("provider" to metadata.id))
                diagnostics += failure("INVALID_PROVIDER", "Provider initialization or declarations failed", metadata.id)
            } catch (error: LinkageError) {
                logging.failure("PROVIDER_LINKAGE_FAILED", error, mapOf("provider" to metadata.id))
                diagnostics += failure("INVALID_PROVIDER", "Provider linkage or initialization failed", metadata.id)
            } catch (error: ServiceConfigurationError) {
                logging.failure("PROVIDER_SERVICE_FAILED", error, mapOf("provider" to metadata.id))
                diagnostics += failure("INVALID_PROVIDER", "Provider service registration failed", metadata.id)
            }
        }
        diagnostics.forEach { ProviderLogs.diagnostic(logging, it) }
        if (providers.isEmpty()) logging.operation("NO_PROVIDERS", mapOf("reason" to "Install a compatible provider and inspect provider registration descriptors"))
        return ProviderRegistry(providers, diagnostics)
    }
}

internal fun failure(code: String, message: String, location: String? = null) =
    Diagnostic(DiagnosticCode(code), message, location = location)

internal fun <T> snapshot(values: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
