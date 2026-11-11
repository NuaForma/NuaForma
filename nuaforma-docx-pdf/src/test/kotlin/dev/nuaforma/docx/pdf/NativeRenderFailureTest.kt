package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.pdf.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class NativeRenderFailureTest {

    private fun provider(validator: PdfConformanceValidator = PdfConformanceValidator { _, _ -> fail("Rendering must reject before validation") }): DocxPdfProvider {
        val font = requireNotNull(javaClass.getResourceAsStream("/own-engine-fonts/LiberationSans-Regular.ttf")).use { it.readBytes() }
        return DocxPdfProvider(NativePdfConfiguration(listOf(NativePdfFont("Liberation Sans", "regular", font)), validator))
    }

    private fun source(text: String, paragraphProperties: String = ""): ByteArray {
        val parts = PdfFixture.parts()
        parts["word/styles.xml"] = parts.getValue("word/styles.xml").decodeToString().replace("DejaVu Sans", "Liberation Sans").toByteArray()
        parts["word/document.xml"] = parts.getValue("word/document.xml").decodeToString()
            .replace("<w:p>", "<w:p>$paragraphProperties").replace("Hello {name}", text).toByteArray()
        return PdfFixture.zip(parts)
    }

    @Test
    fun `real PDF render failures expose the concrete cause only in development including PDF-A-3b`() {
        val cases = listOf(
            Triple(source("Visible text", "<w:pPr><w:ind w:left=\"12000\"/></w:pPr>"), PdfFailureCode.CONTENT_OVERFLOW,
                "Paragraph indentation exceeds the physical page"),
            Triple(source("Visible text \uE000"), PdfFailureCode.MISSING_GLYPH, "The supplied font cannot encode requested text"))
        for ((bytes, failure, explanation) in cases) for (profile in listOf("standard", "pdfa-3b")) for (mode in LoggingMode.entries) {
            val output = ByteArrayOutputStream()
            val events = mutableListOf<GenerationLogEvent>()
            val error = assertThrows(GenerationException::class.java) {
                provider().generate(GenerationRequest(bytes.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                    FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile)))), output,
                    GenerationContext(GenerationLogging(mode, sink = events::add)))
            }
            assertTrue(events.any { it.code == "NATIVE_PDF_RENDER_STARTED" }, "$failure/$profile/$mode")
            assertEquals(0, output.size())
            val diagnostic = error.diagnostics.single()
            assertEquals(DiagnosticCodes.UNSUPPORTED_INPUT, diagnostic.code)
            assertEquals(RejectionReason.NATIVE_PDF_RENDER_REJECTED, diagnostic.rejectionReason)
            assertEquals(failure, (error.cause as PdfRenderException).code)
            assertNull(diagnostic.part)
            assertNull(diagnostic.documentLocation)
            if (mode == LoggingMode.DEVELOPMENT) {
                assertEquals("$failure: $explanation", diagnostic.sourceContext?.explanation)
                assertNull(diagnostic.sourceContext?.construct)
                assertNull(diagnostic.sourceContext?.path)
                assertEquals("", diagnostic.sourceContext?.excerpt)
            } else assertNull(diagnostic.sourceContext)
        }
    }

    @Test
    fun `failure mapping preserves validation cancellation and limit semantics without exposing arbitrary messages`() {
        for (failure in PdfFailureCode.entries) for (mode in LoggingMode.entries) {
            val diagnostic = NativePdfFailureDiagnostics.diagnostic(PdfRenderException(failure, "password=private-value /private/file.docx"), mode)
            val expected = when (failure) {
                PdfFailureCode.CANCELLED -> DiagnosticCodes.CANCELLED
                PdfFailureCode.RESOURCE_LIMIT -> DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED
                PdfFailureCode.VALIDATION_UNAVAILABLE -> DiagnosticCodes.PROVIDER_UNAVAILABLE
                PdfFailureCode.VALIDATION_FAILED -> DiagnosticCodes.GENERATION_FAILED
                else -> DiagnosticCodes.UNSUPPORTED_INPUT
            }
            assertEquals(expected, diagnostic.code)
            assertEquals(RejectionReason.NATIVE_PDF_RENDER_REJECTED, diagnostic.rejectionReason)
            if (mode == LoggingMode.DEVELOPMENT) {
                assertEquals("$failure: No recognized renderer explanation is available", diagnostic.sourceContext?.explanation)
            } else assertNull(diagnostic.sourceContext)
        }
        for (message in listOf("", "x".repeat(10_000), "The supplied font cannot encode requested text\nforged\u202Epassword")) {
            val diagnostic = NativePdfFailureDiagnostics.diagnostic(PdfRenderException(PdfFailureCode.INVALID_RESOURCE, message), LoggingMode.DEVELOPMENT)
            assertEquals("INVALID_RESOURCE: No recognized renderer explanation is available", diagnostic.sourceContext?.explanation)
        }
    }

    @Test
    fun `custom validator failure remains atomic and its private message is omitted in development`() {
        var validated = false
        val provider = provider(PdfConformanceValidator { bytes, _ ->
            assertTrue(bytes.copyOfRange(0, 5).decodeToString().startsWith("%PDF-"))
            validated = true
            throw PdfRenderException(PdfFailureCode.VALIDATION_FAILED, "Private validator path /private/font.ttf token=private-value")
        })
        val output = ByteArrayOutputStream()
        val error = assertThrows(GenerationException::class.java) {
            provider.generate(GenerationRequest(source("Ordinary control").inputStream(), GenerationRequirements(
                FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.PDF, mapOf("profile" to "pdfa-3b")))), output,
                GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
        }
        assertTrue(validated)
        assertEquals(0, output.size())
        val diagnostic = error.diagnostics.single()
        assertEquals(DiagnosticCodes.GENERATION_FAILED, diagnostic.code)
        assertEquals("VALIDATION_FAILED: No recognized renderer explanation is available", diagnostic.sourceContext?.explanation)
    }
}
