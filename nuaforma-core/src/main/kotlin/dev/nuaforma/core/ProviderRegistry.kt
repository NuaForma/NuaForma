package dev.nuaforma.core

import dev.nuaforma.contracts.*
import dev.nuaforma.core.discovery.ClasspathProviders
import java.io.IOException
import java.io.OutputStream
import java.util.ServiceConfigurationError

/** Immutable provider catalog; availability and complete requirements are reassessed for each selection. */
class ProviderRegistry internal constructor(providers: List<RegisteredProvider>, diagnostics: List<Diagnostic>) {
    val providers: List<RegisteredProvider> = snapshot(providers)
    val diagnostics: List<Diagnostic> = snapshot(diagnostics)

    @JvmOverloads
    @Throws(GenerationException::class)
    fun select(requirements: GenerationRequirements, preferredProviderId: String? = null): RegisteredProvider {
        require(preferredProviderId == null || preferredProviderId.isNotBlank()) { "Provider preference must not be blank" }
        val candidates = providers.filter { preferredProviderId == null || it.metadata.id == preferredProviderId }
        if (preferredProviderId != null && candidates.isEmpty()) {
            throw GenerationException(listOf(failure("PROVIDER_NOT_FOUND", "Preferred provider is not registered")) + diagnostics)
        }
        val rejected = diagnostics.toMutableList()
        val matches = mutableListOf<RegisteredProvider>()
        for (candidate in candidates) {
            val id = candidate.metadata.id
            try {
                if (!candidate.capabilities.supports(requirements.input.format, requirements.output.format)) {
                    rejected += failure("UNSUPPORTED_FORMAT_PAIR", "Provider reads ${candidate.capabilities.readFormats.map { it.id }.sorted()} and writes ${candidate.capabilities.writeFormats.map { it.id }.sorted()}", id)
                    continue
                }
                val availability = candidate.provider.availability()
                availability.validateAgainst(candidate.capabilities)
                if (!availability.supports(requirements.input.format, requirements.output.format)) {
                    rejected += availability.diagnostics
                    rejected += failure("PROVIDER_UNAVAILABLE", "Requested format pair is currently unavailable", id)
                    continue
                }
                val assessment = candidate.provider.assess(requirements)
                if (assessment.status == AssessmentStatus.SUPPORTED) matches += candidate
                else rejected += assessment.diagnostics
            } catch (error: Exception) {
                rejected += failure("INVALID_PROVIDER", "Provider availability or assessment failed", id)
            } catch (error: LinkageError) {
                rejected += failure("INVALID_PROVIDER", "Provider linkage failed during assessment", id)
            } catch (error: ServiceConfigurationError) {
                rejected += failure("INVALID_PROVIDER", "Provider service failed during assessment", id)
            }
        }
        if (matches.size > 1) {
            throw GenerationException(listOf(failure("AMBIGUOUS_PROVIDER", "Multiple providers match: ${matches.map { it.metadata.id }.sorted()}; choose an explicit provider")))
        }
        return matches.singleOrNull() ?: throw GenerationException(
            listOf(failure("NO_MATCHING_PROVIDER", "No compatible, ready provider satisfies the complete request")) + rejected,
        )
    }

    @JvmOverloads
    @Throws(GenerationException::class, IOException::class)
    fun generate(
        request: GenerationRequest,
        output: OutputStream,
        context: GenerationContext,
        preferredProviderId: String? = null,
    ): GenerationResult {
        context.checkActive()
        val selected = select(request.requirements, preferredProviderId)
        context.checkActive()
        val result = try {
            requireNotNull(selected.provider.generate(request, output, context))
        } catch (error: GenerationException) {
            throw error
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw GenerationException(listOf(failure("INVALID_PROVIDER", "Provider generation failed", selected.metadata.id)))
        } catch (error: LinkageError) {
            throw GenerationException(listOf(failure("INVALID_PROVIDER", "Provider linkage failed during generation", selected.metadata.id)))
        } catch (error: ServiceConfigurationError) {
            throw GenerationException(listOf(failure("INVALID_PROVIDER", "Provider service failed during generation", selected.metadata.id)))
        }
        context.checkActive()
        if (result.output.format != request.requirements.output.format) {
            throw GenerationException(listOf(failure("INVALID_PROVIDER_RESULT", "Provider returned a different output format", selected.metadata.id)))
        }
        return result
    }

    companion object {
        @JvmStatic
        fun registered(registrations: List<ProviderRegistration>): ProviderRegistry {
            require((registrations as List<*>).none { it == null }) { "Registrations must not be null" }
            return ProviderDiscovery(ContractVersion.CURRENT).load(registrations.toList())
        }

        @JvmStatic
        @JvmOverloads
        fun discover(
            classLoader: ClassLoader = Thread.currentThread().contextClassLoader ?: DocumentProvider::class.java.classLoader,
            registrations: List<ProviderRegistration> = emptyList(),
        ): ProviderRegistry {
            require((registrations as List<*>).none { it == null }) { "Registrations must not be null" }
            val scanned = ClasspathProviders(classLoader).scan()
            return ProviderDiscovery(ContractVersion.CURRENT).load(scanned.registrations + registrations, scanned.diagnostics)
        }
    }
}
