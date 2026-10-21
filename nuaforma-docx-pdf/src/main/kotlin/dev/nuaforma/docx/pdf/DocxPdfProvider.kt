package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.DocxProvider
import dev.nuaforma.docx.DeploymentFonts
import dev.nuaforma.docx.pdf.adapters.libreoffice.LibreOfficeProcesses
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionCopy
import dev.nuaforma.docx.pdf.adapters.ooxml.PdfFonts
import dev.nuaforma.docx.pdf.internal.*
import java.io.OutputStream
import java.nio.file.Files
import dev.nuaforma.template.TemplatePipes

/** DOCX processing is delegated unchanged; only PDF uses an isolated conversion copy. */
class DocxPdfProvider private constructor(configuration: LibreOfficeConfiguration?, private val valuePipes: TemplatePipes) : DocumentProvider, FontCatalogProvider, ContentPipeProvider, TemplatePipeProvider, ProviderReadiness, ConformancePolicyProvider {
    @JvmOverloads
    constructor(configuration: LibreOfficeConfiguration? = null) : this(configuration, TemplatePipes.discover())
    private var pipes: List<ContentPipe> = emptyList()
    private val deploymentFonts = DeploymentFonts.configured()
    private var docx = DocxProvider(deploymentFonts, emptyList(), valuePipes)
    constructor(configuration: LibreOfficeConfiguration?, pipes: List<ContentPipe>) : this(configuration) {
        registerContent(pipes)
    }
    constructor(configuration: LibreOfficeConfiguration?, pipes: List<ContentPipe>, valuePipes: TemplatePipes) : this(configuration, valuePipes) {
        registerContent(pipes)
    }
    private fun registerContent(pipes: List<ContentPipe>) {
        require(pipes.size <= 127) { "Content pipe registrations exceed the generator limit including built-in image" }
        this.pipes = java.util.Collections.unmodifiableList(pipes.map { implementation ->
            val captured = implementation.definition
            require(captured.support.all { support -> support.input == DocumentFormat.DOCX &&
                (support.output == DocumentFormat.DOCX && support.profiles.isEmpty() ||
                    support.output == DocumentFormat.PDF && support.profiles.all { profile -> PdfProfile.entries.any { it.id == profile } })
            }) { "Content pipe declares an unsupported PDF generator combination" }
            object : ContentPipe {
                override val definition = captured
                override fun apply(value: Any?, arguments: List<Any?>, context: ContentPipeContext): ContentResult =
                    implementation.apply(value, arguments, context)
            }
        })
        docx = DocxProvider(deploymentFonts, this.pipes, valuePipes)
    }
    override fun templatePipes(): List<TemplatePipeDefinition> = docx.templatePipes()
    override fun contentPipes(): List<ContentPipeDefinition> = java.util.Collections.unmodifiableList(
        listOf(docx.contentPipes().first { it.name == "image" }.let { image ->
            ContentPipeDefinition(image.name, image.description + "; PDF/A-1b requires opaque source images", image.arguments,
                listOf(ContentPipeSupport(DocumentFormat.DOCX, DocumentFormat.DOCX),
                    ContentPipeSupport(DocumentFormat.DOCX, DocumentFormat.PDF, PdfProfile.entries.map { it.id }.toSet())), image.nullBehavior, image.contentTypes)
        }) + pipes.map { it.definition })
    private val runtimeConfiguration = configuration ?: PdfRuntimeConfiguration.read(System.getProperties())
    private val pdfaValidation = runtimeConfiguration?.pdfaValidation ?: PdfRuntimeConfiguration.validation(System.getProperties())
    private val converter: PdfConverter = LibreOfficeProcesses(runtimeConfiguration)

    override fun conformancePolicies(): List<ConformancePolicy> = java.util.Collections.singletonList(
        ConformancePolicy(DocumentFormat.PDF, PdfProfile.entries.filter { it != PdfProfile.STANDARD }.map { it.id }.toSet(), pdfaValidation))

    override fun fontCatalog(context: GenerationContext): FontCatalog {
        context.checkActive()
        deploymentFonts.reportFailures(context.logging)
        converter.verify(context)
        ConversionFiles.create(context).use { files ->
            val mounted = deploymentFonts.catalog.fonts
            val covered = mounted.map { it.family.lowercase(java.util.Locale.ROOT) to it.face }.toSet()
            return FontCatalog(mounted + converter.fontCatalog(files, context).filter {
                (it.family.lowercase(java.util.Locale.ROOT) to it.face) !in covered
            })
        }
    }

    override fun capabilities(): ProviderCapabilities {
        val original = docx.capabilities()
        return ProviderCapabilities(setOf(DocumentFormat.DOCX), setOf(DocumentFormat.DOCX, DocumentFormat.PDF),
            outputOptions = mapOf(DocumentFormat.DOCX to original.outputOptions.getValue(DocumentFormat.DOCX),
                DocumentFormat.PDF to listOf(
                    OptionDefinition("version", OptionType.STRING, "Derived from profile: 1.4 for PDF/A-1b, otherwise 1.7; explicit values must match", allowedValues = listOf("1.4", "1.7")),
                    OptionDefinition("profile", OptionType.STRING, "Standard PDF or PDF/A export profile; conformance checking follows provider operator policy; no silent downgrade", defaultValue = "pdfa-3b", allowedValues = PdfProfile.entries.map { it.id }),
                    OptionDefinition("fontMode", OptionType.STRING, "Preserve available or template-embedded fonts, or validate/embed private supplied fonts for static PDF", defaultValue = "preserve", allowedValues = listOf("preserve", "validate", "embed")),
                    OptionDefinition("fonts", OptionType.ARRAY, "Private family/face/resource declarations; PDF print embedding permissions apply"),
                    OptionDefinition("paragraphDefaults", OptionType.OBJECT, "Explicit optional afterTwips and line240ths fallback for missing paragraph defaults in the PDF copy only"))),
            generalOptions = original.generalOptions)
    }

    override fun availability(): ProviderAvailability {
        val status = converter.readiness()
        return ProviderAvailability(setOf(DocumentFormat.DOCX),
            if (status.status == AssessmentStatus.SUPPORTED) setOf(DocumentFormat.DOCX, DocumentFormat.PDF) else setOf(DocumentFormat.DOCX), status.diagnostics)
    }

    override fun availability(context: GenerationContext): ProviderAvailability {
        context.checkActive()
        deploymentFonts.reportFailures(context.logging)
        val status = try { converter.verify(context); CapabilityAssessment(AssessmentStatus.SUPPORTED) }
            catch (error: GenerationException) {
                context.checkActive()
                if (error.diagnostics.any { it.code == DiagnosticCodes.CANCELLED || it.code == DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED }) throw error
                context.logging.operation("PDF_BACKEND_UNAVAILABLE", mapOf("reason" to "Check the configured qualified renderer, validators and kernel namespace/isolation support"))
                CapabilityAssessment(AssessmentStatus.UNAVAILABLE, listOf(Diagnostic(DiagnosticCodes.PROVIDER_UNAVAILABLE,
                    RejectionReason.CONFIGURE_THE_QUALIFIED_ISOLATED_LIBREOFFICE_RUNTIME_DOCX_OUTPUT_REMAINS)))
            } catch (error: java.io.IOException) {
                context.logging.failure("PDF_BACKEND_IO_FAILED", error)
                CapabilityAssessment(AssessmentStatus.UNAVAILABLE, listOf(Diagnostic(DiagnosticCodes.PROVIDER_UNAVAILABLE,
                    RejectionReason.CONFIGURE_THE_QUALIFIED_ISOLATED_LIBREOFFICE_RUNTIME_DOCX_OUTPUT_REMAINS)))
            }
        return ProviderAvailability(setOf(DocumentFormat.DOCX),
            if (status.status == AssessmentStatus.SUPPORTED) setOf(DocumentFormat.DOCX, DocumentFormat.PDF) else setOf(DocumentFormat.DOCX), status.diagnostics)
    }

    override fun assess(requirements: GenerationRequirements, context: GenerationContext): CapabilityAssessment {
        context.checkActive()
        deploymentFonts.reportFailures(context.logging)
        val validation = validate(requirements)
        if (validation.status != AssessmentStatus.SUPPORTED || requirements.output.format == DocumentFormat.DOCX) return validation
        val available = availability(context)
        return if (available.supports(requirements.input.format, requirements.output.format)) CapabilityAssessment(AssessmentStatus.SUPPORTED)
            else CapabilityAssessment(AssessmentStatus.UNAVAILABLE, available.diagnostics)
    }

    override fun assess(requirements: GenerationRequirements): CapabilityAssessment {
        val validation = validate(requirements)
        if (validation.status != AssessmentStatus.SUPPORTED || requirements.output.format == DocumentFormat.DOCX) return validation
        return converter.readiness()
    }

    private fun validate(requirements: GenerationRequirements): CapabilityAssessment {
        if (requirements.output.format == DocumentFormat.DOCX) return docx.assess(requirements)
        if (requirements.output.format != DocumentFormat.PDF) return CapabilityAssessment(AssessmentStatus.UNSUPPORTED,
            listOf(Diagnostic(DiagnosticCodes.UNSUPPORTED_OUTPUT, RejectionReason.THIS_PROVIDER_WRITES_DOCX_AND_PDF_ONLY)))
        return try {
            val options = PdfOptions.read(requirements.output.options)
            if (options.profile == PdfProfile.PDFA_1B && requirements.options.watermark?.opacity?.let { it != 1.0 } == true) {
                PdfFailure.fail(DiagnosticCodes.UNSUPPORTED_OPTION, RejectionReason.PDF_A_1B_SUPPORTS_OPAQUE_WATERMARKS_ONLY_CHOOSE_PDF)
            }
            docx.assess(GenerationRequirements(requirements.input, FormatRequest(DocumentFormat.DOCX, options.docxOptions), requirements.options))
        } catch (error: GenerationException) { CapabilityAssessment(AssessmentStatus.UNSUPPORTED, error.diagnostics) }
    }

    override fun generate(request: GenerationRequest, output: OutputStream, context: GenerationContext): GenerationResult {
        context.checkActive()
        deploymentFonts.reportFailures(context.logging)
        val status = validate(request.requirements)
        if (status.status != AssessmentStatus.SUPPORTED) throw GenerationException(status.diagnostics)
        if (request.requirements.output.format == DocumentFormat.DOCX) return docx.generate(request, output, context)
        converter.verify(context)
        val options = PdfOptions.read(request.requirements.output.options)
        var modernInline = false
        ConversionFiles.create(context).use { files ->
            files.profile = options.profile
            val bound = deploymentFonts.bind(request)
            val supplied = PdfFonts.supplied(bound, files, context)
            val intermediate = GenerationRequest(request.template,
                GenerationRequirements(request.requirements.input, FormatRequest(DocumentFormat.DOCX), request.requirements.options), request.data)
            // PDF resources are validated privately using print permissions, not DOCX editable embedding.
            val applicable = pipes.filter { pipe -> pipe.definition.support.any {
                it.input == DocumentFormat.DOCX && it.output == DocumentFormat.PDF && (it.profiles.isEmpty() || options.profile.id in it.profiles)
            } }
            Files.newOutputStream(files.docx).use { DocxProvider(deploymentFonts, applicable, valuePipes).generate(intermediate, it, context) }
            val (required, embedded) = PdfFonts.required(files, context)
            val mounted = deploymentFonts.sourcesFor(required, supplied + embedded)
            val mountedRequest = GenerationRequest(request.template, GenerationRequirements(request.requirements.input,
                FormatRequest(DocumentFormat.PDF, mapOf("fonts" to deploymentFonts.declarations(mounted.keys)))), request.data, mounted)
            val staged = PdfFonts.supplied(mountedRequest, files, context, "mounted-")
            PdfFonts.ensureAvailable(required, supplied + embedded + staged + converter.fontFamilies(files, context))
            modernInline = ConversionCopy.prepare(files.docx, files.conversionCopy, options, context).modernInlineTextBoxes
            converter.convert(files, context)
            PdfStreams.checkFraming(files.pdf, context, options.profile.version)
            converter.validate(files, context)
            PdfStreams.publish(files.pdf, output, context, options.profile.version)
        }
        return GenerationResult(FormatRequest(DocumentFormat.PDF, request.requirements.output.options + mapOf("version" to options.profile.version, "profile" to options.profile.id)),
            if (modernInline) listOf(Diagnostic(DiagnosticCode("PDF_TEXTBOX_LAYOUT_LIMITATION"),
                "Modern inline text boxes may show the temporarily accepted measured vertical difference of approximately 0.60 mm; see NuaForma issue #32", Severity.WARNING)) else emptyList())
    }
}
