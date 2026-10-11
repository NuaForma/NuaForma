package dev.nuaforma.contracts

import java.io.IOException
import java.io.OutputStream
import java.util.Collections

/**
 * Every read/write pair is supported at the base-format level.
 * Options may constrain individual requests; assess() is authoritative for complete combinations.
 */
class ProviderCapabilities @JvmOverloads constructor(
    readFormats: Set<DocumentFormat>,
    writeFormats: Set<DocumentFormat>,
    inputOptions: Map<DocumentFormat, List<OptionDefinition>> = emptyMap(),
    outputOptions: Map<DocumentFormat, List<OptionDefinition>> = emptyMap(),
    generalOptions: List<OptionDefinition> = emptyList(),
) {
    val readFormats: Set<DocumentFormat> = immutableSet(readFormats)
    val writeFormats: Set<DocumentFormat> = immutableSet(writeFormats)
    val inputOptions: Map<DocumentFormat, List<OptionDefinition>> = optionCopy(inputOptions)
    val outputOptions: Map<DocumentFormat, List<OptionDefinition>> = optionCopy(outputOptions)
    val generalOptions: List<OptionDefinition> = definitionCopy(generalOptions)

    init {
        require(this.readFormats.isNotEmpty() && this.writeFormats.isNotEmpty()) { "Declared format sets must not be empty" }
        require(this.readFormats.containsAll(this.inputOptions.keys)) { "Input options refer to an undeclared format" }
        require(this.writeFormats.containsAll(this.outputOptions.keys)) { "Output options refer to an undeclared format" }
    }

    fun supports(input: DocumentFormat, output: DocumentFormat): Boolean =
        input in readFormats && output in writeFormats

    private fun optionCopy(values: Map<DocumentFormat, List<OptionDefinition>>): Map<DocumentFormat, List<OptionDefinition>> {
        require((values as Map<*, *>).keys.none { it == null }) { "Option format keys must not be null" }
        return Collections.unmodifiableMap(values.mapValues { definitionCopy(it.value) })
    }

    private fun definitionCopy(values: List<OptionDefinition>): List<OptionDefinition> {
        val copy = immutableList(values)
        require(copy.map { it.name }.distinct().size == copy.size) { "Option names must be unique within a scope" }
        return copy
    }
}

/** Usable sets must be subsets of declared sets and guarantee their complete Cartesian product. */
class ProviderAvailability @JvmOverloads constructor(
    readFormats: Set<DocumentFormat>,
    writeFormats: Set<DocumentFormat>,
    diagnostics: List<Diagnostic> = emptyList(),
) {
    val readFormats: Set<DocumentFormat> = immutableSet(readFormats)
    val writeFormats: Set<DocumentFormat> = immutableSet(writeFormats)
    val diagnostics: List<Diagnostic> = immutableList(diagnostics)

    init {
        require(this.readFormats.isEmpty() == this.writeFormats.isEmpty()) { "Unavailable providers must report both sets empty" }
        require(this.readFormats.isNotEmpty() || this.diagnostics.any { it.severity == Severity.ERROR }) {
            "Unavailable providers require an error diagnostic"
        }
    }

    fun supports(input: DocumentFormat, output: DocumentFormat): Boolean =
        input in readFormats && output in writeFormats

    fun validateAgainst(declared: ProviderCapabilities) {
        require(declared.readFormats.containsAll(readFormats) && declared.writeFormats.containsAll(writeFormats)) {
            "Availability must not expand declared support"
        }
    }
}

enum class AssessmentStatus { SUPPORTED, UNSUPPORTED, UNAVAILABLE }

class CapabilityAssessment @JvmOverloads constructor(
    val status: AssessmentStatus,
    diagnostics: List<Diagnostic> = emptyList(),
) {
    val diagnostics: List<Diagnostic> = immutableList(diagnostics)

    init {
        val hasErrors = this.diagnostics.any { it.severity == Severity.ERROR }
        require(if (status == AssessmentStatus.SUPPORTED) !hasErrors else hasErrors) {
            "Rejected assessments need errors; supported assessments must not contain errors"
        }
    }
}

/**
 * Trusted executable integration, not a sandbox for third-party code.
 * Methods must be safe for concurrent requests with isolated per-generation mutable state.
 */
interface DocumentProvider {
    fun capabilities(): ProviderCapabilities
    fun availability(): ProviderAvailability

    /** Reject unknown options and unsupported versions, profiles and combined requirements without consuming inputs. */
    fun assess(requirements: GenerationRequirements): CapabilityAssessment

    /**
     * Recheck readiness, validate template content, honor limits and close only provider-owned resources.
     * Never close caller-owned template/output streams. A failure may leave partial bytes in an output stream.
     */
    @Throws(GenerationException::class, IOException::class)
    fun generate(request: GenerationRequest, output: OutputStream, context: GenerationContext): GenerationResult
}
