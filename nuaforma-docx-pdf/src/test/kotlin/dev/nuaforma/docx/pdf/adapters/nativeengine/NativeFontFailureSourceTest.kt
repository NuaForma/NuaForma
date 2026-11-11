package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.PdfFixture
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeFontFailureSourceTest {

    private fun read(error: GenerationException): GenerationException {
        val context = GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))
        return assertThrows(GenerationException::class.java) {
            NativeDocxReader(context) { _, _, _ -> throw error }.read(PdfFixture.zip(PdfFixture.parts()))
        }
    }

    @Test
    fun `locating a font failure preserves other diagnostics and unnamed font errors`() {
        val located = Diagnostic(DiagnosticCodes.INVALID_REQUEST, RejectionReason.USED_FONT_FACE_UNAVAILABLE)
            .withSourceContext(DocumentSourceContext("Missing font family: Sample; variant: regular", null, null, ""))
        val unrelated = Diagnostic(DiagnosticCodes.GENERATION_FAILED, RejectionReason.NATIVE_PDF_RENDER_REJECTED)
            .withSourceContext(DocumentSourceContext("Retained original failure", null, null, ""))
        val unnamed = Diagnostic(DiagnosticCodes.INVALID_REQUEST, RejectionReason.USED_FONT_FACE_UNAVAILABLE)
        val error = GenerationException(listOf(located, unrelated, unnamed))
        val result = read(error)
        assertEquals(3, result.diagnostics.size)
        assertEquals(located.sourceContext!!.explanation, result.diagnostics[0].sourceContext?.explanation)
        assertNotNull(result.diagnostics[0].part)
        assertNotNull(result.diagnostics[0].sourceContext?.path)
        assertSame(unrelated, result.diagnostics[1])
        assertSame(unnamed, result.diagnostics[2])
        assertNull(result.diagnostics[2].sourceContext)
    }

    @Test
    fun `reader does not invent details or replace an exception without a named font failure`() {
        for (diagnostic in listOf(
            Diagnostic(DiagnosticCodes.INVALID_REQUEST, RejectionReason.USED_FONT_FACE_UNAVAILABLE),
            Diagnostic(DiagnosticCodes.GENERATION_FAILED, RejectionReason.NATIVE_PDF_RENDER_REJECTED)
                .withSourceContext(DocumentSourceContext("Keep this explanation", null, null, "")))) {
            val error = GenerationException(listOf(diagnostic))
            assertSame(error, read(error))
        }
    }
}
