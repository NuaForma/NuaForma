package dev.nuaforma.contracts

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.OutputStream

class CapabilitiesTest {
    @Test
    fun `format identifiers are extensible and canonical`() {
        assertEquals(DocumentFormat("docx"), DocumentFormat.DOCX)
        assertEquals("acme.report", DocumentFormat("acme.report").id)
        for (invalid in listOf("", "PDF", "../pdf", "application/pdf", "pdf ".trimEnd() + " ")) {
            assertThrows(IllegalArgumentException::class.java) { DocumentFormat(invalid) }
        }
    }

    @Test
    fun `all declared read write pairs are supported and snapshots are immutable`() {
        val custom = DocumentFormat("custom")
        val reads = linkedSetOf(DocumentFormat.DOCX, custom)
        val writes = linkedSetOf(DocumentFormat.DOCX, DocumentFormat.PDF)
        val declared = ProviderCapabilities(reads, writes)
        reads.clear()
        writes.clear()
        for (input in declared.readFormats) {
            for (output in declared.writeFormats) assertTrue(declared.supports(input, output))
        }
        assertFalse(declared.supports(DocumentFormat.PDF, DocumentFormat.PDF))
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (declared.writeFormats as MutableSet<DocumentFormat>).clear()
        }
        assertThrows(IllegalArgumentException::class.java) { ProviderCapabilities(emptySet(), setOf(DocumentFormat.PDF)) }
    }

    @Test
    fun `missing conversion can leave DOCX usable and cannot expand capabilities`() {
        val declared = ProviderCapabilities(setOf(DocumentFormat.DOCX), setOf(DocumentFormat.DOCX, DocumentFormat.PDF))
        val available = ProviderAvailability(
            setOf(DocumentFormat.DOCX), setOf(DocumentFormat.DOCX),
            listOf(Diagnostic(DiagnosticCodes.PROVIDER_UNAVAILABLE, "PDF converter is not configured")),
        )
        available.validateAgainst(declared)
        assertTrue(available.supports(DocumentFormat.DOCX, DocumentFormat.DOCX))
        assertFalse(available.supports(DocumentFormat.DOCX, DocumentFormat.PDF))
        assertThrows(IllegalArgumentException::class.java) {
            ProviderAvailability(setOf(DocumentFormat.PDF), setOf(DocumentFormat.PDF)).validateAgainst(declared)
        }
        assertThrows(IllegalArgumentException::class.java) { ProviderAvailability(emptySet(), setOf(DocumentFormat.PDF)) }
        assertThrows(IllegalArgumentException::class.java) { ProviderAvailability(emptySet(), emptySet()) }
        val unavailable = ProviderAvailability(
            emptySet(), emptySet(), listOf(Diagnostic(DiagnosticCodes.PROVIDER_UNAVAILABLE, "Provider unavailable")),
        )
        assertFalse(unavailable.supports(DocumentFormat.DOCX, DocumentFormat.DOCX))
    }

    @Test
    fun `option descriptions advertise required values defaults and choices without leaking mutation`() {
        val allowed = mutableListOf<Any?>("1.7", "2.0")
        val definition = OptionDefinition("version", OptionType.STRING, "PDF version", defaultValue = "1.7", allowedValues = allowed)
        val definitions = mutableListOf(definition)
        val map = linkedMapOf(DocumentFormat.PDF to definitions as List<OptionDefinition>)
        val declared = ProviderCapabilities(
            setOf(DocumentFormat.DOCX), setOf(DocumentFormat.PDF), outputOptions = map,
        )
        allowed.clear()
        definitions.clear()
        map.clear()
        assertEquals(listOf("1.7", "2.0"), declared.outputOptions.getValue(DocumentFormat.PDF).single().allowedValues)
        assertEquals("1.7", definition.defaultValue)
        assertThrows(IllegalArgumentException::class.java) {
            OptionDefinition("version", OptionType.STRING, "Version", defaultValue = 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OptionDefinition("version", OptionType.STRING, "Version", true, "1.7")
        }
        assertThrows(IllegalArgumentException::class.java) {
            OptionDefinition("version", OptionType.STRING, "Version", defaultValue = "3.0", allowedValues = listOf("1.7"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderCapabilities(setOf(DocumentFormat.DOCX), setOf(DocumentFormat.PDF), outputOptions = mapOf(
                DocumentFormat.PDF to listOf(definition, definition),
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderCapabilities(setOf(DocumentFormat.DOCX), setOf(DocumentFormat.DOCX), outputOptions = mapOf(
                DocumentFormat.PDF to listOf(definition),
            ))
        }
    }

    @Test
    fun `assessment rejects unknown options and unavailable or incompatible combinations`() {
        val provider = AssessmentProvider()
        fun requirements(output: DocumentFormat, options: Map<String, *> = emptyMap<String, Any?>()) =
            GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(output, options))

        assertEquals(AssessmentStatus.SUPPORTED, provider.assess(requirements(DocumentFormat.DOCX)).status)
        assertEquals(AssessmentStatus.SUPPORTED, provider.assess(requirements(DocumentFormat.PDF)).status)
        assertEquals(AssessmentStatus.SUPPORTED, provider.assess(requirements(
            DocumentFormat.PDF, mapOf("version" to "1.7", "profile" to "pdf/a-2u", "embedFonts" to true),
        )).status)
        for (options in listOf(
            mapOf("version" to "2.0", "profile" to "pdf/a-2u"),
            mapOf("profile" to "pdf/a-2u", "embedFonts" to false),
            mapOf("version" to "3.0"),
            mapOf("version" to 1.7),
            mapOf("unknown" to true),
            mapOf("version" to null),
        )) {
            assertEquals(AssessmentStatus.UNSUPPORTED, provider.assess(requirements(DocumentFormat.PDF, options)).status)
        }
        provider.converterReady = false
        assertEquals(AssessmentStatus.UNAVAILABLE, provider.assess(requirements(DocumentFormat.PDF)).status)
        assertEquals(AssessmentStatus.SUPPORTED, provider.assess(requirements(DocumentFormat.DOCX)).status)
    }

    @Test
    fun `assessments and success results cannot hide errors`() {
        val error = Diagnostic(DiagnosticCodes.UNSUPPORTED_OPTION, "Unknown option")
        val warning = Diagnostic(DiagnosticCode("ACME.WARNING"), "Synthetic warning", Severity.WARNING)
        assertThrows(IllegalArgumentException::class.java) { CapabilityAssessment(AssessmentStatus.SUPPORTED, listOf(error)) }
        assertThrows(IllegalArgumentException::class.java) { CapabilityAssessment(AssessmentStatus.UNSUPPORTED) }
        assertThrows(IllegalArgumentException::class.java) { GenerationResult(FormatRequest(DocumentFormat.PDF), listOf(error)) }
        assertThrows(IllegalArgumentException::class.java) { GenerationException(listOf(warning)) }
        assertEquals(listOf(warning), GenerationResult(FormatRequest(DocumentFormat.PDF), listOf(warning)).warnings)
    }

    /** Assessment fixture only: it does not generate or prove fidelity of real documents. */
    private class AssessmentProvider : DocumentProvider {
        var converterReady = true
        override fun capabilities() = ProviderCapabilities(setOf(DocumentFormat.DOCX), setOf(DocumentFormat.DOCX, DocumentFormat.PDF))
        override fun availability() = ProviderAvailability(
            setOf(DocumentFormat.DOCX), if (converterReady) setOf(DocumentFormat.DOCX, DocumentFormat.PDF) else setOf(DocumentFormat.DOCX),
        )

        override fun assess(requirements: GenerationRequirements): CapabilityAssessment {
            fun reject(status: AssessmentStatus, code: DiagnosticCode) =
                CapabilityAssessment(status, listOf(Diagnostic(code, "Synthetic requirement rejection")))
            if (!capabilities().supports(requirements.input.format, requirements.output.format)) {
                return reject(AssessmentStatus.UNSUPPORTED, DiagnosticCodes.UNSUPPORTED_OUTPUT)
            }
            if (!availability().supports(requirements.input.format, requirements.output.format)) {
                return reject(AssessmentStatus.UNAVAILABLE, DiagnosticCodes.PROVIDER_UNAVAILABLE)
            }
            val options = requirements.output.options
            val known = if (requirements.output.format == DocumentFormat.PDF) setOf("version", "profile", "embedFonts") else emptySet()
            if (options.keys.any { it !in known } || requirements.input.options.isNotEmpty() ||
                requirements.options.extensions.isNotEmpty()) {
                return reject(AssessmentStatus.UNSUPPORTED, DiagnosticCodes.UNSUPPORTED_OPTION)
            }
            if (requirements.output.format == DocumentFormat.PDF) {
                val version = if (options.containsKey("version")) options["version"] else "1.7"
                val profile = if (options.containsKey("profile")) options["profile"] else "standard"
                val embed = if (options.containsKey("embedFonts")) options["embedFonts"] else true
                if (version !in setOf("1.7", "2.0") || profile !in setOf("standard", "pdf/a-2u") ||
                    embed !is Boolean || (profile == "pdf/a-2u" && (version != "1.7" || !embed))) {
                    return reject(AssessmentStatus.UNSUPPORTED, DiagnosticCodes.UNSUPPORTED_OPTION)
                }
            }
            return CapabilityAssessment(AssessmentStatus.SUPPORTED)
        }

        override fun generate(request: GenerationRequest, output: OutputStream, context: GenerationContext): GenerationResult =
            throw UnsupportedOperationException("Assessment-only test fixture")
    }
}
