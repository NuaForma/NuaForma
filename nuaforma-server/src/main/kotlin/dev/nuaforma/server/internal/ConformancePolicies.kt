package dev.nuaforma.server.internal

import dev.nuaforma.contracts.*

internal object ConformancePolicies {
    fun describe(provider: DocumentProvider, declared: ProviderCapabilities, context: GenerationContext): List<Map<String, Any>>? {
        if (provider !is ConformancePolicyProvider) return null
        return try {
            context.checkActive()
            val policies = provider.conformancePolicies().toList()
            require(policies.size <= 128)
            val combinations = policies.flatMap { policy ->
                require(policy.outputFormat in declared.writeFormats)
                val profiles = declared.outputOptions[policy.outputFormat]?.singleOrNull { it.name == "profile" }?.allowedValues.orEmpty()
                require(profiles.containsAll(policy.profiles))
                policy.profiles.map { policy.outputFormat to it }
            }
            require(combinations.distinct().size == combinations.size)
            context.checkActive()
            policies.sortedBy { it.outputFormat.id + it.profiles.sorted().joinToString() }.map {
                mapOf("outputFormat" to it.outputFormat.id, "profiles" to it.profiles.sorted(), "validationEnabled" to it.validationEnabled)
            }
        } catch (error: Exception) {
            context.checkActive()
            context.logging.failure("CONFORMANCE_POLICY_UNAVAILABLE", error)
            null
        } catch (error: LinkageError) {
            context.logging.failure("CONFORMANCE_POLICY_UNAVAILABLE", error)
            null
        } catch (error: java.util.ServiceConfigurationError) {
            context.logging.failure("CONFORMANCE_POLICY_UNAVAILABLE", error)
            null
        }
    }
}
