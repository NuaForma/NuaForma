package dev.nuaforma.docx

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.adapters.docx4j.Docx4jDocuments
import dev.nuaforma.docx.internal.FontPolicy
import dev.nuaforma.docx.internal.WatermarkPolicy
import dev.nuaforma.docx.internal.ImagePipe
import java.io.OutputStream

/** Stateless DOCX-to-DOCX provider; each call owns a separate document and template session. */
class DocxProvider @JvmOverloads constructor(private val deploymentFonts: DeploymentFonts = DeploymentFonts.configured()) : DocumentProvider, FontCatalogProvider, ContentPipeProvider {
    private var pipes: List<ContentPipe> = emptyList()
    private var definitions: List<ContentPipeDefinition> = emptyList()

    /** Keep the original constructors, including Kotlin's default-argument bytecode entry point. */
    constructor(deploymentFonts: DeploymentFonts, pipes: List<ContentPipe>) : this(deploymentFonts) {
        require(pipes.size <= 127) { "Content pipe registrations exceed the generator limit including built-in image" }
        this.pipes = java.util.Collections.unmodifiableList(pipes.map { implementation ->
            val captured = implementation.definition
            object : ContentPipe {
                override val definition = captured
                override fun apply(value: Any?, arguments: List<Any?>, context: ContentPipeContext): ContentResult =
                    implementation.apply(value, arguments, context)
            }
        })
        definitions = this.pipes.map { it.definition }
        require(definitions.map { it.name }.distinct().size == definitions.size && definitions.none { it.name == "image" }) { "Content pipe registrations must be unique; image is reserved" }
        require(definitions.all { definition -> definition.support.any {
            it.input == DocumentFormat.DOCX && it.output == DocumentFormat.DOCX && it.profiles.isEmpty()
        } }) { "DOCX content pipes must declare unprofiled DOCX-to-DOCX support" }
    }
    override fun contentPipes(): List<ContentPipeDefinition> = java.util.Collections.unmodifiableList(listOf(ImagePipe.DEFINITION) + definitions.map { definition ->
        ContentPipeDefinition(definition.name, definition.description, definition.arguments,
            definition.support.filter { it.input == DocumentFormat.DOCX && it.output == DocumentFormat.DOCX && it.profiles.isEmpty() }, definition.nullBehavior,
            definition.contentTypes)
    })
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
        Docx4jDocuments.generate(deploymentFonts.bind(request), output, context, listOf(ImagePipe(context)) + pipes)
        return GenerationResult(request.requirements.output)
    }
}
