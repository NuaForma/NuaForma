package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.DocxProvider
import dev.nuaforma.docx.pdf.adapters.libreoffice.LibreOfficeProcesses
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionCopy
import dev.nuaforma.docx.pdf.adapters.ooxml.PdfFonts
import dev.nuaforma.docx.pdf.internal.*
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path

/** DOCX processing is delegated unchanged; only PDF uses an isolated conversion copy. */
class DocxPdfProvider @JvmOverloads constructor(configuration: LibreOfficeConfiguration? = null) : DocumentProvider {
    private val docx = DocxProvider()
    private val converter: PdfConverter = LibreOfficeProcesses(configuration ?: System.getProperty("nuaforma.pdf.launcher")?.let {
        LibreOfficeConfiguration(Path.of(it))
    })

    override fun capabilities(): ProviderCapabilities {
        val original = docx.capabilities()
        return ProviderCapabilities(setOf(DocumentFormat.DOCX), setOf(DocumentFormat.DOCX, DocumentFormat.PDF),
            outputOptions = mapOf(DocumentFormat.DOCX to original.outputOptions.getValue(DocumentFormat.DOCX),
                DocumentFormat.PDF to listOf(
                    OptionDefinition("version", OptionType.STRING, "Qualified PDF version; no silent downgrade", defaultValue = "1.7", allowedValues = listOf("1.7")),
                    OptionDefinition("profile", OptionType.STRING, "Standard PDF only; archival/accessibility conformance is not claimed", defaultValue = "standard", allowedValues = listOf("standard")),
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

    override fun assess(requirements: GenerationRequirements): CapabilityAssessment {
        val validation = validate(requirements)
        if (validation.status != AssessmentStatus.SUPPORTED || requirements.output.format == DocumentFormat.DOCX) return validation
        return converter.readiness()
    }

    private fun validate(requirements: GenerationRequirements): CapabilityAssessment {
        if (requirements.output.format == DocumentFormat.DOCX) return docx.assess(requirements)
        if (requirements.output.format != DocumentFormat.PDF) return CapabilityAssessment(AssessmentStatus.UNSUPPORTED,
            listOf(Diagnostic(DiagnosticCodes.UNSUPPORTED_OUTPUT, "This provider writes DOCX and PDF only")))
        return try {
            val options = PdfOptions.read(requirements.output.options)
            docx.assess(GenerationRequirements(requirements.input, FormatRequest(DocumentFormat.DOCX, options.docxOptions), requirements.options))
        } catch (error: GenerationException) { CapabilityAssessment(AssessmentStatus.UNSUPPORTED, error.diagnostics) }
    }

    override fun generate(request: GenerationRequest, output: OutputStream, context: GenerationContext): GenerationResult {
        context.checkActive()
        val status = validate(request.requirements)
        if (status.status != AssessmentStatus.SUPPORTED) throw GenerationException(status.diagnostics)
        if (request.requirements.output.format == DocumentFormat.DOCX) return docx.generate(request, output, context)
        converter.verify(context)
        val options = PdfOptions.read(request.requirements.output.options)
        var modernInline = false
        ConversionFiles.create(context).use { files ->
            val supplied = PdfFonts.supplied(request, files, context)
            val intermediate = GenerationRequest(request.template,
                GenerationRequirements(request.requirements.input, FormatRequest(DocumentFormat.DOCX), request.requirements.options), request.data)
            // PDF resources are validated privately using print permissions, not DOCX editable embedding.
            Files.newOutputStream(files.docx).use { docx.generate(intermediate, it, context) }
            val (required, embedded) = PdfFonts.required(files, context)
            PdfFonts.ensureAvailable(required, supplied + embedded + converter.fontFamilies(files, context))
            modernInline = ConversionCopy.prepare(files.docx, files.conversionCopy, options, context).modernInlineTextBoxes
            converter.convert(files, context)
            PdfStreams.checkFraming(files.pdf, context)
            converter.validate(files, context)
            PdfStreams.publish(files.pdf, output, context)
        }
        return GenerationResult(FormatRequest(DocumentFormat.PDF, request.requirements.output.options + mapOf("version" to "1.7", "profile" to "standard")),
            if (modernInline) listOf(Diagnostic(DiagnosticCode("PDF_TEXTBOX_LAYOUT_LIMITATION"),
                "Modern inline text boxes may show the temporarily accepted measured vertical difference of approximately 0.60 mm; see NuaForma issue #32", Severity.WARNING)) else emptyList())
    }
}
