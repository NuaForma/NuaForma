package dev.nuaforma.contracts

/** Extensible machine-readable code; messages must not contain raw input or generated content. */
data class DiagnosticCode(val id: String) {
    init {
        require(Regex("[A-Z][A-Z0-9_.-]{0,127}").matches(id)) { "Invalid diagnostic code" }
    }
}

object DiagnosticCodes {
    @JvmField val INVALID_REQUEST = DiagnosticCode("INVALID_REQUEST")
    @JvmField val UNSUPPORTED_INPUT = DiagnosticCode("UNSUPPORTED_INPUT")
    @JvmField val UNSUPPORTED_OUTPUT = DiagnosticCode("UNSUPPORTED_OUTPUT")
    @JvmField val UNSUPPORTED_OPTION = DiagnosticCode("UNSUPPORTED_OPTION")
    @JvmField val PROVIDER_UNAVAILABLE = DiagnosticCode("PROVIDER_UNAVAILABLE")
    @JvmField val INVALID_TEMPLATE = DiagnosticCode("INVALID_TEMPLATE")
    @JvmField val MISSING_VALUE = DiagnosticCode("MISSING_VALUE")
    @JvmField val RESOURCE_LIMIT_EXCEEDED = DiagnosticCode("RESOURCE_LIMIT_EXCEEDED")
    @JvmField val CANCELLED = DiagnosticCode("CANCELLED")
    @JvmField val GENERATION_FAILED = DiagnosticCode("GENERATION_FAILED")
    @JvmField val INVALID_PROVIDER_METADATA = DiagnosticCode("INVALID_PROVIDER_METADATA")
}

enum class Severity { WARNING, ERROR }

class Diagnostic @JvmOverloads constructor(
    val code: DiagnosticCode,
    val message: String,
    val severity: Severity = Severity.ERROR,
    val location: String? = null,
) {
    var rejectionReason: RejectionReason? = null
        private set
    var part: String? = null
        private set
    var templateContext: TemplateErrorContext? = null
        private set
    var documentLocation: String? = null
        private set

    /** Trusted adapters may enrich a diagnostic without changing existing constructor descriptors. */
    fun withTemplateContext(value: TemplateErrorContext?): Diagnostic = copy().also { it.templateContext = value }
    fun withDocumentLocation(value: String?): Diagnostic = copy().also { it.documentLocation = value }

    private fun copy(): Diagnostic = (rejectionReason?.let { Diagnostic(code, it, severity, location, part) }
        ?: Diagnostic(code, message, severity, location)).also {
        it.templateContext = templateContext
        it.documentLocation = documentLocation
    }

    /** Additive structured explanation; original String constructors retain their JVM descriptors. */
    @JvmOverloads
    constructor(code: DiagnosticCode, reason: RejectionReason, severity: Severity = Severity.ERROR, location: String? = null, part: String? = null) :
        this(code, reason.message, severity, location) {
        rejectionReason = reason
        this.part = part
    }

    init {
        require(message.isNotBlank()) { "A diagnostic message is required" }
        require(location == null || location.isNotBlank()) { "A diagnostic location must not be blank" }
    }
}

class GenerationException @JvmOverloads constructor(
    diagnostics: List<Diagnostic>,
    cause: Throwable? = null,
) : Exception("Document generation failed", cause) {
    val diagnostics: List<Diagnostic> = immutableList(diagnostics)

    init {
        require(this.diagnostics.any { it.severity == Severity.ERROR }) { "Failure requires an error diagnostic" }
    }
}

/** Effective output options are reported by the provider; success must never hide a downgrade. */
class GenerationResult @JvmOverloads constructor(
    val output: FormatRequest,
    warnings: List<Diagnostic> = emptyList(),
) {
    val warnings: List<Diagnostic> = immutableList(warnings)

    init {
        require(this.warnings.all { it.severity == Severity.WARNING }) { "Success cannot contain error diagnostics" }
    }
}
