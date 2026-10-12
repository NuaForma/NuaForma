package dev.nuaforma.docx

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.adapters.docx4j.Docx4jDocuments
import dev.nuaforma.docx.internal.FontPolicy
import dev.nuaforma.docx.internal.WatermarkPolicy
import java.io.OutputStream

/** Stateless DOCX-to-DOCX provider; each call owns a separate document and template session. */
class DocxProvider @JvmOverloads constructor(private val deploymentFonts: DeploymentFonts = DeploymentFonts.configured()) : DocumentProvider, FontCatalogProvider {
    override fun fontCatalog(context: GenerationContext): FontCatalog { context.checkActive(); return deploymentFonts.catalog }
    override fun capabilities() = ProviderCapabilities(setOf(DocumentFormat.DOCX), setOf(DocumentFormat.DOCX),
        outputOptions = mapOf(DocumentFormat.DOCX to listOf(
            OptionDefinition("fontMode", OptionType.STRING, "Preserve template fonts, validate supplied families, or embed supplied font faces", defaultValue = "preserve", allowedValues = listOf("preserve", "validate", "embed")),
            OptionDefinition("fonts", OptionType.ARRAY, "Bounded font family/face/resource declarations; embedding follows OpenType permissions"))),
        generalOptions = listOf(OptionDefinition("watermark", OptionType.OBJECT, "Optional text watermark using the template language and request data, with color, opacity and rotation; existing header objects are retained")))
    override fun availability() = ProviderAvailability(setOf(DocumentFormat.DOCX), setOf(DocumentFormat.DOCX))

    override fun assess(requirements: GenerationRequirements): CapabilityAssessment {
        val errors = mutableListOf<Diagnostic>()
        if (requirements.input.format != DocumentFormat.DOCX) errors.add(Diagnostic(DiagnosticCodes.UNSUPPORTED_INPUT, "This provider reads DOCX only"))
        if (requirements.output.format != DocumentFormat.DOCX) errors.add(Diagnostic(DiagnosticCodes.UNSUPPORTED_OUTPUT, "This provider writes DOCX only"))
        if (requirements.input.options.isNotEmpty() || requirements.output.options.keys.any { it !in setOf("fontMode", "fonts") } ||
            requirements.options.extensions.isNotEmpty()) {
            errors.add(Diagnostic(DiagnosticCodes.UNSUPPORTED_OPTION, "Unrecognized DOCX options"))
        }
        try { FontPolicy.read(requirements.output.options) } catch (error: GenerationException) { errors.addAll(error.diagnostics) }
        try { WatermarkPolicy.validate(requirements.options.watermark) } catch (error: GenerationException) { errors.addAll(error.diagnostics) }
        return CapabilityAssessment(if (errors.isEmpty()) AssessmentStatus.SUPPORTED else AssessmentStatus.UNSUPPORTED, errors)
    }

    override fun generate(request: GenerationRequest, output: OutputStream, context: GenerationContext): GenerationResult {
        context.checkActive()
        val assessment = assess(request.requirements)
        if (assessment.status != AssessmentStatus.SUPPORTED) throw GenerationException(assessment.diagnostics)
        Docx4jDocuments.generate(deploymentFonts.bind(request), output, context)
        return GenerationResult(request.requirements.output)
    }
}
