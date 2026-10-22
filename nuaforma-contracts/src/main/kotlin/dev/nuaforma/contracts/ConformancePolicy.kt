package dev.nuaforma.contracts

/** Optional immutable operator policy metadata. Absence means unknown, never a file certificate. */
interface ConformancePolicyProvider {
    fun conformancePolicies(): List<ConformancePolicy>
}

/** Applies only to the listed export profiles; generation support is declared separately. */
class ConformancePolicy(val outputFormat: DocumentFormat, profiles: Set<String>, val validationEnabled: Boolean) {
    val profiles: Set<String> = immutableSet(profiles)
    init {
        require(this.profiles.isNotEmpty() && this.profiles.size <= 128)
        require(this.profiles.all { it.matches(Regex("[a-z][a-z0-9.+-]{0,127}")) })
    }
}
