package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.LoggingMode
import dev.nuaforma.contracts.RejectionReason
import dev.nuaforma.pdf.PdfFailureCode
import dev.nuaforma.pdf.PdfRenderException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeFrameOverflowDiagnosticsTest {
    @Test
    fun frameOverflowMeasurementsAreVisibleOnlyInDevelopment() {
        for (label in listOf("Text exceeds frame content height", "Text frame crosses a physical page side",
            "Auto-fitted text frame exceeds page width", "Auto-fitted text frame exceeds page height",
            "Text frame crosses the physical page top or bottom")) {
            val measured = "$label; required=134.546875 pt; available=121.6 pt"
            val exception = PdfRenderException(PdfFailureCode.CONTENT_OVERFLOW, measured)
            val preview = NativePdfFailureDiagnostics.diagnostic(exception, LoggingMode.DEVELOPMENT)
            assertEquals(RejectionReason.NATIVE_PDF_RENDER_REJECTED, preview.rejectionReason)
            assertEquals("CONTENT_OVERFLOW: $measured", preview.sourceContext?.explanation)
            assertNull(NativePdfFailureDiagnostics.diagnostic(exception, LoggingMode.PRODUCTION).sourceContext)
        }
    }

    @Test
    fun unknownTextAfterKnownReasonIsWithheld() {
        val original = PdfRenderException(PdfFailureCode.CONTENT_OVERFLOW,
            "Text exceeds frame content height; required=134.5 pt; available=121.6 pt; EXTRA")
        assertEquals("CONTENT_OVERFLOW: No recognized renderer explanation is available",
            NativePdfFailureDiagnostics.diagnostic(original, LoggingMode.DEVELOPMENT).sourceContext?.explanation)
    }
}
