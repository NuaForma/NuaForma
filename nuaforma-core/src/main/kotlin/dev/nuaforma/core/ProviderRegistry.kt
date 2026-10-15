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
    private val readinessReports = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

    @JvmOverloads
    @Throws(GenerationException::class)
    fun select(requirements: GenerationRequirements, preferredProviderId: String? = null): RegisteredProvider {
        return select(requirements, preferredProviderId, GenerationContext())
    }

    fun select(requirements: GenerationRequirements, preferredProviderId: String?, context: GenerationContext): RegisteredProvider {
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
                val availability = availability(candidate, context)
                availability.validateAgainst(candidate.capabilities)
                if (!availability.supports(requirements.input.format, requirements.output.format)) {
                    rejected += availability.diagnostics
                    rejected += failure("PROVIDER_UNAVAILABLE", "Requested format pair is currently unavailable", id)
                    continue
                }
                val assessment = if (candidate.provider is ProviderReadiness) candidate.provider.assess(requirements, context) else candidate.provider.assess(requirements)
                if (assessment.status == AssessmentStatus.SUPPORTED) matches += candidate
                else rejected += assessment.diagnostics
            } catch (error: Exception) {
                context.checkActive()
                context.logging.failure("PROVIDER_ASSESSMENT_FAILED", error, mapOf("provider" to id))
                rejected += failure("INVALID_PROVIDER", "Provider availability or assessment failed", id)
            } catch (error: LinkageError) {
                context.logging.failure("PROVIDER_LINKAGE_FAILED", error, mapOf("provider" to id))
                rejected += failure("INVALID_PROVIDER", "Provider linkage failed during assessment", id)
            } catch (error: ServiceConfigurationError) {
                context.logging.failure("PROVIDER_SERVICE_FAILED", error, mapOf("provider" to id))
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
        val selected = select(request.requirements, preferredProviderId, context)
        context.logging.operation("GENERATION_START", mapOf("provider" to selected.metadata.id))
        context.checkActive()
        val result = try {
            requireNotNull(selected.provider.generate(request, output, context))
        } catch (error: GenerationException) {
            throw error
        } catch (error: IOException) {
            context.logging.failure("PROVIDER_IO_FAILED", error, mapOf("provider" to selected.metadata.id))
            throw error
        } catch (error: Exception) {
            context.logging.failure("PROVIDER_GENERATION_FAILED", error, mapOf("provider" to selected.metadata.id))
            throw GenerationException(listOf(failure("INVALID_PROVIDER", "Provider generation failed", selected.metadata.id)))
        } catch (error: LinkageError) {
            context.logging.failure("PROVIDER_LINKAGE_FAILED", error, mapOf("provider" to selected.metadata.id))
            throw GenerationException(listOf(failure("INVALID_PROVIDER", "Provider linkage failed during generation", selected.metadata.id)))
        } catch (error: ServiceConfigurationError) {
            context.logging.failure("PROVIDER_SERVICE_FAILED", error, mapOf("provider" to selected.metadata.id))
            throw GenerationException(listOf(failure("INVALID_PROVIDER", "Provider service failed during generation", selected.metadata.id)))
        }
        context.checkActive()
        if (result.output.format != request.requirements.output.format) {
            throw GenerationException(listOf(failure("INVALID_PROVIDER_RESULT", "Provider returned a different output format", selected.metadata.id)))
        }
        context.logging.operation("GENERATION_COMPLETED", mapOf("provider" to selected.metadata.id))
        return result
    }

    fun availability(provider: RegisteredProvider, context: GenerationContext): ProviderAvailability {
        require(provider in providers) { "Provider is not registered" }
        val available = if (provider.provider is ProviderReadiness) provider.provider.availability(context) else provider.provider.availability()
        available.validateAgainst(provider.capabilities)
        if (!context.logging.enabled) return available
        val fingerprint = available.readFormats.map { it.id }.sorted().joinToString(",") + "/" +
            available.writeFormats.map { it.id }.sorted().joinToString(",") + "/" + available.diagnostics.map { it.code.id }.sorted().joinToString(",")
        val now = System.nanoTime()
        var report = false
        readinessReports.compute(provider.metadata.id) { _, previous ->
            if (previous == null || previous.first != fingerprint || now - previous.second >= java.util.concurrent.TimeUnit.SECONDS.toNanos(30)) {
                report = true
                fingerprint to now
            } else previous
        }
        if (report) ProviderLogs.availability(context.logging, provider.metadata.id, provider.capabilities, available)
        return available
    }

    companion object {
        @JvmStatic
        fun registered(registrations: List<ProviderRegistration>): ProviderRegistry {
            require((registrations as List<*>).none { it == null }) { "Registrations must not be null" }
            return ProviderDiscovery(ContractVersion.CURRENT).load(registrations.toList())
        }

        @JvmStatic
        fun registered(registrations: List<ProviderRegistration>, logging: GenerationLogging): ProviderRegistry {
            require((registrations as List<*>).none { it == null }) { "Registrations must not be null" }
            return ProviderDiscovery(ContractVersion.CURRENT, logging).load(registrations.toList())
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

        @JvmStatic
        fun discover(classLoader: ClassLoader, registrations: List<ProviderRegistration>, logging: GenerationLogging): ProviderRegistry {
            require((registrations as List<*>).none { it == null }) { "Registrations must not be null" }
            val scanned = ClasspathProviders(classLoader, logging).scan()
            return ProviderDiscovery(ContractVersion.CURRENT, logging).load(scanned.registrations + registrations, scanned.diagnostics)
        }
    }
}
