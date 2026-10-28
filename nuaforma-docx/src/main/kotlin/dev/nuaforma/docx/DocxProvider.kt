package dev.nuaforma.docx

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.adapters.docx4j.Docx4jDocuments
import dev.nuaforma.docx.internal.FontPolicy
import dev.nuaforma.docx.internal.WatermarkPolicy
import dev.nuaforma.docx.internal.ImagePipe
import java.io.OutputStream
import dev.nuaforma.template.TemplatePipes

/** Stateless DOCX-to-DOCX provider; each call owns a separate document and template session. */
class DocxProvider private constructor(private val deploymentFonts: DeploymentFonts, private val valuePipes: TemplatePipes) : DocumentProvider, FontCatalogProvider, ContentPipeProvider, TemplatePipeProvider, ProviderReadiness {
    @JvmOverloads
    constructor(deploymentFonts: DeploymentFonts = DeploymentFonts.configured()) : this(deploymentFonts, TemplatePipes.discover())
    private var pipes: List<ContentPipe> = emptyList()
    private var definitions: List<ContentPipeDefinition> = emptyList()

    /** Keep the original constructors, including Kotlin's default-argument bytecode entry point. */
    constructor(deploymentFonts: DeploymentFonts, pipes: List<ContentPipe>) : this(deploymentFonts) {
        registerContent(pipes)
    }
    constructor(deploymentFonts: DeploymentFonts, pipes: List<ContentPipe>, valuePipes: TemplatePipes) : this(deploymentFonts, valuePipes) {
        registerContent(pipes)
    }
    private fun registerContent(pipes: List<ContentPipe>) {
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
        checkPipeNames()
        require(definitions.all { definition -> definition.support.any {
            it.input == DocumentFormat.DOCX && it.output == DocumentFormat.DOCX && it.profiles.isEmpty()
        } }) { "DOCX content pipes must declare unprofiled DOCX-to-DOCX support" }
    }
    init { checkPipeNames() }
    private fun checkPipeNames() {
        require(valuePipes.definitions().none { value -> value.name == "image" || definitions.any { it.name == value.name } }) {
            "Value and content pipe names must not collide"
        }
    }
    override fun templatePipes(): List<TemplatePipeDefinition> = valuePipes.definitions()
    override fun contentPipes(): List<ContentPipeDefinition> = java.util.Collections.unmodifiableList(listOf(ImagePipe.DEFINITION) + definitions.map { definition ->
        ContentPipeDefinition(definition.name, definition.description, definition.arguments,
            definition.support.filter { it.input == DocumentFormat.DOCX && it.output == DocumentFormat.DOCX && it.profiles.isEmpty() }, definition.nullBehavior,
            definition.contentTypes)
    })
    override fun fontCatalog(context: GenerationContext): FontCatalog { context.checkActive(); deploymentFonts.reportFailures(context.logging); return deploymentFonts.catalog }
    override fun capabilities() = ProviderCapabilities(setOf(DocumentFormat.DOCX), setOf(DocumentFormat.DOCX),
        outputOptions = mapOf(DocumentFormat.DOCX to listOf(
            OptionDefinition("fontMode", OptionType.STRING, "Preserve template fonts, validate supplied families, or embed supplied font faces", defaultValue = "preserve", allowedValues = listOf("preserve", "validate", "embed")),
            OptionDefinition("fontSelection", OptionType.STRING, "Declared resources, or actually used supported font faces; used requires fontMode embed", defaultValue = "declared", allowedValues = listOf("declared", "used")),
            OptionDefinition("fonts", OptionType.ARRAY, "Bounded font family/face/resource declarations; embedding follows OpenType permissions"))),
        generalOptions = listOf(OptionDefinition("watermark", OptionType.OBJECT, "Optional text watermark using the template language and request data, with color, opacity and rotation; existing header objects are retained")))
    override fun availability() = ProviderAvailability(setOf(DocumentFormat.DOCX), setOf(DocumentFormat.DOCX))
    override fun availability(context: GenerationContext): ProviderAvailability {
        context.checkActive()
        deploymentFonts.reportFailures(context.logging)
        return availability()
    }
    override fun assess(requirements: GenerationRequirements, context: GenerationContext): CapabilityAssessment {
        availability(context)
        return assess(requirements)
    }

    override fun assess(requirements: GenerationRequirements): CapabilityAssessment {
        val errors = mutableListOf<Diagnostic>()
        if (requirements.input.format != DocumentFormat.DOCX) errors.add(Diagnostic(DiagnosticCodes.UNSUPPORTED_INPUT, RejectionReason.THIS_PROVIDER_READS_DOCX_ONLY))
        if (requirements.output.format != DocumentFormat.DOCX) errors.add(Diagnostic(DiagnosticCodes.UNSUPPORTED_OUTPUT, RejectionReason.THIS_PROVIDER_WRITES_DOCX_ONLY))
        if (requirements.input.options.isNotEmpty() || requirements.output.options.keys.any { it !in setOf("fontMode", "fontSelection", "fonts") } ||
            requirements.options.extensions.isNotEmpty()) {
            errors.add(Diagnostic(DiagnosticCodes.UNSUPPORTED_OPTION, RejectionReason.UNRECOGNIZED_DOCX_OPTIONS))
        }
        try { FontPolicy.read(requirements.output.options) } catch (error: GenerationException) { errors.addAll(error.diagnostics) }
        try { WatermarkPolicy.validate(requirements.options.watermark) } catch (error: GenerationException) { errors.addAll(error.diagnostics) }
        return CapabilityAssessment(if (errors.isEmpty()) AssessmentStatus.SUPPORTED else AssessmentStatus.UNSUPPORTED, errors)
    }

    override fun generate(request: GenerationRequest, output: OutputStream, context: GenerationContext): GenerationResult =
        generateDocument(request, output, context, false).generation

    /** Resolve the watermark in the document's template session, leaving placement to the converter. */
    @Throws(GenerationException::class, java.io.IOException::class)
    fun generateForConversion(request: GenerationRequest, output: OutputStream, context: GenerationContext): DocxConversionResult =
        generateDocument(request, output, context, true)

    /** Explicit development copy: validate template syntax, mark the original source and leave instructions literal. */
    @Throws(GenerationException::class, java.io.IOException::class)
    fun diagnosticCopy(request: GenerationRequest, output: OutputStream, context: GenerationContext, explanationFontFamily: String): Diagnostic =
        Docx4jDocuments.diagnosticCopy(request, output, context, listOf(ImagePipe(context)) + pipes, valuePipes, explanationFontFamily)

    private fun generateDocument(request: GenerationRequest, output: OutputStream, context: GenerationContext, detachWatermark: Boolean): DocxConversionResult {
        context.checkActive()
        deploymentFonts.reportFailures(context.logging)
        val assessment = assess(request.requirements)
        if (assessment.status != AssessmentStatus.SUPPORTED) throw GenerationException(assessment.diagnostics)
        val decoration = Docx4jDocuments.generate(deploymentFonts.bind(request), output, context, listOf(ImagePipe(context)) + pipes, valuePipes, deploymentFonts, detachWatermark)
        return DocxConversionResult(GenerationResult(request.requirements.output), decoration.first, decoration.second)
    }
}
