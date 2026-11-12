package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.pdf.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class NativeMeasuredOverflowTest {

    @Test
    fun `measured renderer failures retain the complete exact reason and values only in development`() {
        val messages = listOf(
            "Word and leading spaces exceed line width; required=900.0 pt; available=80.0 pt",
            "Tab alignment moves behind current text; required=10.0 pt; available=-9.0 pt",
            "Page break requires another layout area; required=2.0 areas; available=1.0 areas",
            "Automatic line height is not finite; required=Infinity pt; available=14400.0 pt",
            "Automatic line height is not finite; required=NaN pt; available=14400.0 pt",
            "Automatic line height is below natural content height; required=1.4E-45 pt; available=0.0 pt")
        for (message in messages) for (mode in LoggingMode.entries) {
            val diagnostic = NativePdfFailureDiagnostics.diagnostic(PdfRenderException(PdfFailureCode.CONTENT_OVERFLOW, message), mode)
            assertEquals(DiagnosticCodes.UNSUPPORTED_INPUT, diagnostic.code)
            if (mode == LoggingMode.DEVELOPMENT) {
                assertEquals("CONTENT_OVERFLOW: $message", diagnostic.sourceContext?.explanation)
                assertTrue(diagnostic.sourceContext!!.explanation.length <= 256)
            } else assertNull(diagnostic.sourceContext)
        }
        for (message in listOf(
            "Word width exceeds line width; required=password pt; available=80.0 pt",
            "Word width exceeds line width; required=90.0 pt; available=80.0 areas",
            "Word width exceeds line width; required=90.0 pt; available=80.0 pt\nforged",
            "Word width exceeds line width; required=90.0 pt; available=80.0 pt\u202E",
            "Private document text; required=90.0 pt; available=80.0 pt",
            "Word width exceeds line width; required=" + "1".repeat(1000) + " pt; available=80.0 pt")) {
            val diagnostic = NativePdfFailureDiagnostics.diagnostic(PdfRenderException(PdfFailureCode.CONTENT_OVERFLOW, message), LoggingMode.DEVELOPMENT)
            assertEquals("CONTENT_OVERFLOW: No recognized renderer explanation is available", diagnostic.sourceContext?.explanation)
        }
        val wrongKind = NativePdfFailureDiagnostics.diagnostic(PdfRenderException(PdfFailureCode.INVALID_RESOURCE, messages.first()), LoggingMode.DEVELOPMENT)
        assertEquals("INVALID_RESOURCE: No recognized renderer explanation is available", wrongKind.sourceContext?.explanation)
    }

    @Test
    fun `real DOCX long-word rejection survives PDF-A-3b and standard conversion without diagnostic fallback`() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/own-engine-fonts/LiberationSans-Regular.ttf")).use { it.readBytes() }
        val provider = DocxPdfProvider(NativePdfConfiguration(listOf(NativePdfFont("Liberation Sans", "regular", bytes)),
            PdfConformanceValidator { _, _ -> fail("Oversized word must reject before validation") }))
        val parts = PdfFixture.parts()
        parts["word/styles.xml"] = parts.getValue("word/styles.xml").decodeToString().replace("DejaVu Sans", "Liberation Sans").toByteArray()
        parts["word/document.xml"] = parts.getValue("word/document.xml").decodeToString()
            .replace("<w:b/>", "").replace("Hello {name}", "W".repeat(200)).toByteArray()
        for (profile in listOf("standard", "pdfa-3b")) for (mode in LoggingMode.entries) {
            val output = ByteArrayOutputStream()
            val error = assertThrows(GenerationException::class.java) {
                provider.generate(GenerationRequest(PdfFixture.zip(parts).inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                    FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile)))), output, GenerationContext(GenerationLogging(mode)))
            }
            val renderer = error.cause as PdfRenderException
            assertEquals(PdfFailureCode.CONTENT_OVERFLOW, renderer.code)
            val matches = Regex("Word and leading spaces exceed line width; required=([0-9.]+) pt; available=([0-9.]+) pt").matchEntire(renderer.message!!)
            assertNotNull(matches, renderer.message)
            assertTrue(matches!!.groupValues[1].toFloat() > matches.groupValues[2].toFloat())
            val diagnostic = error.diagnostics.single()
            if (mode == LoggingMode.DEVELOPMENT) assertEquals("CONTENT_OVERFLOW: ${renderer.message}", diagnostic.sourceContext?.explanation)
            else assertNull(diagnostic.sourceContext)
            assertEquals(0, output.size())
        }
    }
}
