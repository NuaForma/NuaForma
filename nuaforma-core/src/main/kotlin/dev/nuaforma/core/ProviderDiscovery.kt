package dev.nuaforma.core

import dev.nuaforma.contracts.*
import java.util.Collections
import java.util.ServiceConfigurationError

internal class ProviderDiscovery(private val availableVersion: ContractVersion) {
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
                diagnostics += failure("INCOMPATIBLE_CONTRACT", "Provider requires contract ${metadata.requiredContract}; available contract is $availableVersion", metadata.id)
                continue
            }
            try {
                val provider = registration.factory.get()
                require(provider.javaClass.name == metadata.implementationClass)
                val capabilities = provider.capabilities()
                provider.availability().validateAgainst(capabilities)
                providers += RegisteredProvider(metadata, capabilities, provider)
            } catch (error: Exception) {
                diagnostics += failure("INVALID_PROVIDER", "Provider initialization or declarations failed", metadata.id)
            } catch (error: LinkageError) {
                diagnostics += failure("INVALID_PROVIDER", "Provider linkage or initialization failed", metadata.id)
            } catch (error: ServiceConfigurationError) {
                diagnostics += failure("INVALID_PROVIDER", "Provider service registration failed", metadata.id)
            }
        }
        return ProviderRegistry(providers, diagnostics)
    }
}

internal fun failure(code: String, message: String, location: String? = null) =
    Diagnostic(DiagnosticCode(code), message, location = location)

internal fun <T> snapshot(values: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
