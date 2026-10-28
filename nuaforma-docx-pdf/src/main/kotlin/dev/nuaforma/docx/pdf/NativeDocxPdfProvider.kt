package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.DocxProvider
import dev.nuaforma.docx.DeploymentFonts
import dev.nuaforma.template.TemplatePipes
import dev.nuaforma.docx.pdf.adapters.nativeengine.NativeDocxReader
import dev.nuaforma.docx.pdf.adapters.nativeengine.NativeDocxReadException
import dev.nuaforma.docx.pdf.internal.PdfOptions
import dev.nuaforma.docx.pdf.internal.PdfFontMetadata
import dev.nuaforma.pdf.*
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Locale

/** Native implementation shared by direct callers and the registered DOCX/PDF facade. */
class NativeDocxPdfProvider @JvmOverloads constructor(
    private val configuration: NativePdfConfiguration,
    pipes: List<ContentPipe> = emptyList(),
    private val valuePipes: TemplatePipes = TemplatePipes.discover(),
) :
    DocumentProvider, ProviderReadiness, FontCatalogProvider, ConformancePolicyProvider, TemplatePipeProvider, ContentPipeProvider, TemplateDiagnosticProvider {
    private val deploymentFonts = DeploymentFonts.configured()
    private val pipes = pipes.map { implementation ->
        val definition = implementation.definition
        object : ContentPipe {
            override val definition = definition
            override fun apply(value: Any?, arguments: List<Any?>, context: ContentPipeContext) = implementation.apply(value, arguments, context)
        }
    }
    private val docx = DocxProvider(deploymentFonts, this.pipes, valuePipes)

    override fun templatePipes(): List<TemplatePipeDefinition> = docx.templatePipes()
    override fun contentPipes(): List<ContentPipeDefinition> = listOf(docx.contentPipes().first { it.name == "image" }.let { definition ->
        ContentPipeDefinition(definition.name, definition.description, definition.arguments,
            definition.support + ContentPipeSupport(DocumentFormat.DOCX, DocumentFormat.PDF, PdfOutputProfile.entries.map { it.id }.toSet()),
            definition.nullBehavior, definition.contentTypes)
    }) + pipes.map { it.definition }

    override fun fontCatalog(context: GenerationContext): FontCatalog {
        context.checkActive()
        return FontCatalog(configuration.fonts.map { AvailableFont(it.family, it.face, it.origin, it.resourceId) })
    }

    override fun conformancePolicies(): List<ConformancePolicy> = listOf(
        ConformancePolicy(DocumentFormat.PDF, setOf("pdfa-1b", "pdfa-2b", "pdfa-3b"), configuration.pdfaValidation))

    override fun capabilities(): ProviderCapabilities {
        val original = docx.capabilities()
        return ProviderCapabilities(setOf(DocumentFormat.DOCX), setOf(DocumentFormat.DOCX, DocumentFormat.PDF),
            outputOptions = original.outputOptions + (DocumentFormat.PDF to listOf(
                OptionDefinition("profile", OptionType.STRING, "Bounded native DOCX profile; archival output requires independent validation",
                    defaultValue = "pdfa-3b", allowedValues = PdfOutputProfile.entries.map { it.id }),
                OptionDefinition("version", OptionType.STRING, "1.4 for PDF/A-1b, otherwise 1.7; must match profile", allowedValues = listOf("1.4", "1.7")),
                OptionDefinition("fontMode", OptionType.STRING, "Fonts are always embedded for native PDF; supplied faces are checked", defaultValue = "preserve", allowedValues = listOf("preserve", "validate", "embed")),
                OptionDefinition("fonts", OptionType.ARRAY, "Private family/face/resource declarations; no font substitution"))),
            generalOptions = original.generalOptions)
    }

    override fun availability(): ProviderAvailability = availability(GenerationContext())
    override fun availability(context: GenerationContext): ProviderAvailability {
        context.checkActive()
        try {
            val validator = configuration.validator ?: run {
                context.logging.operation("NATIVE_PDF_VALIDATOR_UNAVAILABLE", mapOf("reason" to "Configure the independent validator runtime"))
                return unavailable()
            }
            if (validator is PdfProcessValidator) {
                validator.verify(PdfRenderControl(checkpoint = Runnable { context.checkActive() }))
                context.logging.operation("NATIVE_PDF_VALIDATOR_READY", mapOf("runtime" to "java17"))
            }
            return ProviderAvailability(setOf(DocumentFormat.DOCX), setOf(DocumentFormat.DOCX, DocumentFormat.PDF))
        } catch (_: PdfRenderException) { context.checkActive(); return unavailable() }
    }
    private fun unavailable() = ProviderAvailability(setOf(DocumentFormat.DOCX), setOf(DocumentFormat.DOCX),
        listOf(Diagnostic(DiagnosticCodes.PROVIDER_UNAVAILABLE, RejectionReason.NATIVE_PDF_VALIDATOR_REQUIRED)))

    override fun assess(requirements: GenerationRequirements, context: GenerationContext): CapabilityAssessment {
        context.checkActive()
        val assessment = assess(requirements)
        if (assessment.status != AssessmentStatus.SUPPORTED || requirements.output.format == DocumentFormat.DOCX) return assessment
        val available = availability(context)
        return if (DocumentFormat.PDF in available.writeFormats) assessment else CapabilityAssessment(AssessmentStatus.UNAVAILABLE, available.diagnostics)
    }

    override fun assess(requirements: GenerationRequirements): CapabilityAssessment {
        if (requirements.output.format == DocumentFormat.DOCX) return docx.assess(requirements)
        if (requirements.output.format != DocumentFormat.PDF) return unsupported(DiagnosticCodes.UNSUPPORTED_OUTPUT, RejectionReason.THIS_PROVIDER_WRITES_DOCX_AND_PDF_ONLY)
        return try {
            val options = PdfOptions.read(requirements.output.options)
            if (options.paragraphDefaults != null || options.profile.id == "pdfa-1b" && requirements.options.watermark != null) {
                return unsupported(DiagnosticCodes.UNSUPPORTED_OPTION, RejectionReason.NATIVE_PDF_UNQUALIFIED_OPTION)
            }
            val delegate = docx.assess(GenerationRequirements(requirements.input, FormatRequest(DocumentFormat.DOCX, options.docxOptions), requirements.options))
            if (delegate.status != AssessmentStatus.SUPPORTED) return delegate
            if (configuration.validator == null) {
                CapabilityAssessment(AssessmentStatus.UNAVAILABLE, listOf(Diagnostic(DiagnosticCodes.PROVIDER_UNAVAILABLE, RejectionReason.NATIVE_PDF_VALIDATOR_REQUIRED)))
            } else CapabilityAssessment(AssessmentStatus.SUPPORTED)
        } catch (error: GenerationException) { CapabilityAssessment(AssessmentStatus.UNSUPPORTED, error.diagnostics) }
    }

    override fun diagnoseTemplate(request: GenerationRequest, output: OutputStream, context: GenerationContext): GenerationResult {
        if (context.logging.mode != LoggingMode.DEVELOPMENT) fail(DiagnosticCodes.UNSUPPORTED_OPTION, RejectionReason.TEMPLATE_DIAGNOSTICS_REQUIRE_DEVELOPMENT)
        val declared = capabilities().outputOptions[request.requirements.output.format].orEmpty().map { it.name }.toSet()
        if (request.requirements.output.options.keys.any { it !in declared } || request.requirements.options.extensions.isNotEmpty())
            fail(DiagnosticCodes.UNSUPPORTED_OPTION, RejectionReason.UNRECOGNIZED_PDF_OPTIONS)
        val options = request.requirements.output.options.filterKeys { it in setOf("fonts", "fontMode") } + ("profile" to "standard")
        val requirements = GenerationRequirements(request.requirements.input, FormatRequest(DocumentFormat.PDF, options),
            GenerationOptions(locale = request.requirements.options.locale, timeZone = request.requirements.options.timeZone))
        return render(GenerationRequest(request.template, requirements, request.data, request.resources), output, context, true)
    }

    override fun generate(request: GenerationRequest, output: OutputStream, context: GenerationContext): GenerationResult = render(request, output, context, false)

    private fun render(request: GenerationRequest, output: OutputStream, context: GenerationContext, diagnostic: Boolean): GenerationResult {
        context.checkActive()
        val assessment = assess(request.requirements, context)
        if (assessment.status != AssessmentStatus.SUPPORTED) throw GenerationException(assessment.diagnostics)
        if (request.requirements.output.format == DocumentFormat.DOCX) return docx.generate(request, output, context)
        if (!configuration.admission.tryAcquire()) fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, RejectionReason.NATIVE_PDF_CAPACITY_EXHAUSTED)
        try {
            val options = PdfOptions.read(request.requirements.output.options)
            val fonts = suppliedFonts(request, context)
            val availableFonts = fonts.associateBy { it.family.lowercase(Locale.ROOT) to it.face }
            val usedFonts = linkedMapOf<Pair<String, String>, NativePdfFont>()
            val filled = BoundedBuffer(minOf(8_388_608L, context.limits.maxExpandedBytes), context)
            val intermediate = GenerationRequest(request.template, GenerationRequirements(request.requirements.input,
                FormatRequest(DocumentFormat.DOCX), request.requirements.options), request.data)
            val applicable = pipes.filter { pipe -> pipe.definition.support.any { support -> support.input == DocumentFormat.DOCX && support.output == DocumentFormat.PDF &&
                (support.profiles.isEmpty() || options.profile.id in support.profiles) } }
            val processor = DocxProvider(deploymentFonts, applicable, valuePipes)
            val prepared = if (diagnostic) {
                val family = fonts.firstOrNull { it.face == "regular" }?.family ?: fail(DiagnosticCodes.INVALID_REQUEST, RejectionReason.USED_FONT_FACE_UNAVAILABLE)
                processor.diagnosticCopy(intermediate, filled, context, family)
                null
            } else processor.generateForConversion(intermediate, filled, context)
            val intermediateSize = filled.size()
            fun fontReference(family: String, bold: Boolean, italic: Boolean): String {
                val face = when { bold && italic -> "boldItalic"; bold -> "bold"; italic -> "italic"; else -> "regular" }
                val identity = family.lowercase(Locale.ROOT) to face
                val font = availableFonts[identity] ?: fail(DiagnosticCodes.INVALID_REQUEST, RejectionReason.USED_FONT_FACE_UNAVAILABLE)
                usedFonts.putIfAbsent(identity, font)
                if (usedFonts.size > 16 || usedFonts.values.sumOf { it.byteSize.toLong() } > minOf(33_554_432L, context.limits.maxExpandedBytes - intermediateSize)) {
                    fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, RejectionReason.PDF_CONVERSION_BYTE_BUDGET_WAS_EXCEEDED)
                }
                return "font${usedFonts.keys.indexOf(identity)}"
            }
            val reader = NativeDocxReader(context, ::fontReference)
            val document = reader.read(filled.toByteArray())
            val watermark = prepared?.watermark?.let { resolved ->
                val text = resolved.text.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ').replace('\t', ' ')
                val rgb = resolved.color.removePrefix("#").toInt(16)
                PdfWatermark(text, fontReference(requireNotNull(prepared.watermarkFontFamily), false, false),
                    PdfColor(rgb shr 16, rgb shr 8 and 255, rgb and 255), resolved.opacity.toFloat(), resolved.rotation.toFloat())
            }
            val profile = PdfOutputProfile.entries.single { it.id == options.profile.id }
            val control = PdfRenderControl(minOf(16_777_216L, context.limits.maxOutputBytes), Runnable { context.checkActive() },
                minOf(67_108_864L, context.limits.maxExpandedBytes - filled.size() - usedFonts.values.sumOf { it.byteSize.toLong() }))
            context.logging.operation("NATIVE_PDF_RENDER_STARTED", mapOf("profile" to profile.id))
            val bytes = PdfEngine(configuration.validator).render(document,
                usedFonts.values.mapIndexed { index, font -> font.resource("font$index") }, PdfOutputOptions(profile, validationEnabled = configuration.pdfaValidation), control, watermark)
            if (profile == PdfOutputProfile.STANDARD || !configuration.pdfaValidation) {
                val copy = bytes.copyOf()
                if (profile == PdfOutputProfile.STANDARD) requireNotNull(configuration.validator).validate(copy, profile, control)
                else requireNotNull(configuration.validator).validateStructure(copy, profile, control)
                if (!copy.contentEquals(bytes)) fail(DiagnosticCodes.GENERATION_FAILED, RejectionReason.GENERATED_PDF_FAILED_STATIC_OUTPUT_VALIDATION)
            }
            context.checkActive()
            context.logging.operation("NATIVE_PDF_STRUCTURE_VALIDATED", mapOf("profile" to profile.id))
            if (configuration.pdfaValidation && profile != PdfOutputProfile.STANDARD) {
                context.logging.operation("NATIVE_PDF_CONFORMANCE_VALIDATED", mapOf("profile" to profile.id))
            }
            output.write(bytes)
            return GenerationResult(FormatRequest(DocumentFormat.PDF, request.requirements.output.options + mapOf("version" to options.profile.version, "profile" to profile.id)))
        } catch (error: GenerationException) { throw error }
        catch (error: PdfRenderException) {
            context.checkActive()
            val code = when (error.code) {
                PdfFailureCode.CANCELLED -> DiagnosticCodes.CANCELLED
                PdfFailureCode.RESOURCE_LIMIT -> DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED
                PdfFailureCode.VALIDATION_UNAVAILABLE -> DiagnosticCodes.PROVIDER_UNAVAILABLE
                PdfFailureCode.VALIDATION_FAILED -> DiagnosticCodes.GENERATION_FAILED
                else -> DiagnosticCodes.UNSUPPORTED_INPUT
            }
            throw GenerationException(listOf(Diagnostic(code, RejectionReason.NATIVE_PDF_RENDER_REJECTED)), error)
        } catch (error: NativeDocxReadException) {
            throw GenerationException(listOf(Diagnostic(DiagnosticCodes.UNSUPPORTED_INPUT,
                RejectionReason.NATIVE_DOCX_PROFILE_UNSUPPORTED, location = "ooxml", part = error.part.takeIf { it != "package" })), error)
        } catch (_: IllegalArgumentException) {
            fail(DiagnosticCodes.UNSUPPORTED_INPUT, RejectionReason.NATIVE_DOCX_PROFILE_UNSUPPORTED)
        } finally { configuration.admission.release() }
    }

    private fun suppliedFonts(request: GenerationRequest, context: GenerationContext): List<NativePdfFont> {
        val declarations = request.requirements.output.options["fonts"] as? List<*> ?: emptyList<Any?>()
        val resources = request.resources.toMutableMap()
        for (raw in declarations) {
            val id = (raw as Map<*, *>)["resource"] as String
            if (id !in resources) configuration.fonts.firstOrNull { it.resourceId == id }?.let { resources[id] = it.source() }
        }
        if (resources.keys != declarations.map { (it as Map<*, *>)["resource"] as String }.toSet()) {
            fail(DiagnosticCodes.INVALID_REQUEST, RejectionReason.SUPPLY_EXACTLY_THE_DECLARED_PDF_FONT_RESOURCES)
        }
        if (declarations.size > 16) fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, RejectionReason.PDF_CONVERSION_BYTE_BUDGET_WAS_EXCEEDED)
        var suppliedBytes = 0L
        val supplied = declarations.map { raw ->
            val declaration = raw as Map<*, *>
            val bytes = BoundedBuffer(minOf(8_388_608L, context.limits.maxExpandedBytes - suppliedBytes, 33_554_432L - suppliedBytes), context)
            resources.getValue(declaration["resource"] as String).openStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    context.checkActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    bytes.write(buffer, 0, count)
                }
            }
            suppliedBytes += bytes.size()
            val content = bytes.toByteArray()
            val embedding = if (PdfFontMetadata.read(content).permissions and 256 != 0) PdfFontEmbedding.FULL else PdfFontEmbedding.SUBSET
            try { NativePdfFont(declaration["family"] as String, declaration["face"] as? String ?: "regular", content, embedding) }
            catch (_: IllegalArgumentException) { fail(DiagnosticCodes.INVALID_REQUEST, RejectionReason.PDF_FONT_FAMILY_OR_FACE_DOES_NOT_MATCH_ITS) }
        }
        val replaced = supplied.map { it.family.lowercase(Locale.ROOT) to it.face }.toSet()
        val result = configuration.fonts.filter { (it.family.lowercase(Locale.ROOT) to it.face) !in replaced } + supplied
        return result
    }

    private class BoundedBuffer(private val maximum: Long, private val context: GenerationContext) : ByteArrayOutputStream() {
        private fun checkWrite(length: Int) {
            context.checkActive()
            if (length.toLong() > maximum - size()) fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, RejectionReason.PDF_CONVERSION_BYTE_BUDGET_WAS_EXCEEDED)
        }
        override fun write(value: Int) { checkWrite(1); super.write(value) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) { checkWrite(length); super.write(bytes, offset, length) }
    }

    private fun unsupported(code: DiagnosticCode, reason: RejectionReason) = CapabilityAssessment(AssessmentStatus.UNSUPPORTED, listOf(Diagnostic(code, reason)))
}

private fun fail(code: DiagnosticCode, reason: RejectionReason): Nothing = throw GenerationException(listOf(Diagnostic(code, reason)))
