package dev.nuaforma.docx

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.adapters.docx4j.Docx4jDocuments
import java.io.OutputStream

/** Stateless DOCX-to-DOCX provider; each call owns a separate document and template session. */
class DocxProvider : DocumentProvider {
    override fun capabilities() = ProviderCapabilities(setOf(DocumentFormat.DOCX), setOf(DocumentFormat.DOCX))
    override fun availability() = ProviderAvailability(setOf(DocumentFormat.DOCX), setOf(DocumentFormat.DOCX))

    override fun assess(requirements: GenerationRequirements): CapabilityAssessment {
        val errors = mutableListOf<Diagnostic>()
        if (requirements.input.format != DocumentFormat.DOCX) errors.add(Diagnostic(DiagnosticCodes.UNSUPPORTED_INPUT, "This provider reads DOCX only"))
        if (requirements.output.format != DocumentFormat.DOCX) errors.add(Diagnostic(DiagnosticCodes.UNSUPPORTED_OUTPUT, "This provider writes DOCX only"))
        if (requirements.input.options.isNotEmpty() || requirements.output.options.isNotEmpty() ||
            requirements.options.extensions.isNotEmpty() || requirements.options.watermark != null) {
            errors.add(Diagnostic(DiagnosticCodes.UNSUPPORTED_OPTION, "DOCX format extensions and requested watermarks are not implemented"))
        }
        return CapabilityAssessment(if (errors.isEmpty()) AssessmentStatus.SUPPORTED else AssessmentStatus.UNSUPPORTED, errors)
    }

    override fun generate(request: GenerationRequest, output: OutputStream, context: GenerationContext): GenerationResult {
        context.checkActive()
        val assessment = assess(request.requirements)
        if (assessment.status != AssessmentStatus.SUPPORTED) throw GenerationException(assessment.diagnostics)
        if (request.resources.isNotEmpty()) throw GenerationException(listOf(Diagnostic(
            DiagnosticCodes.UNSUPPORTED_OPTION, "DOCX resource injection is not implemented; use resources already in the template")))
        Docx4jDocuments.generate(request, output, context)
        return GenerationResult(request.requirements.output)
    }
}
