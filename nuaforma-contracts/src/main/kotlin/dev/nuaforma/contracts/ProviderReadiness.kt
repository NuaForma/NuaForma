package dev.nuaforma.contracts

/** Optional contextual assessment; existing DocumentProvider implementations remain valid. */
interface ProviderReadiness {
    fun availability(context: GenerationContext): ProviderAvailability
    fun assess(requirements: GenerationRequirements, context: GenerationContext): CapabilityAssessment
}
