package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.pdf.PdfConformanceValidator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class NativeMissingFontDiagnosticTest {

    private fun provider(): DocxPdfProvider {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/own-engine-fonts/LiberationSans-Regular.ttf")).use { it.readBytes() }
        return DocxPdfProvider(NativePdfConfiguration(listOf(NativePdfFont("Liberation Sans", "regular", bytes)),
            PdfConformanceValidator { _, _ -> fail("Missing fonts must fail before validation") }))
    }

    private fun source(family: String, properties: String): ByteArray {
        val parts = PdfFixture.parts()
        parts["word/styles.xml"] = parts.getValue("word/styles.xml").decodeToString().replace("DejaVu Sans", "Liberation Sans").toByteArray()
        parts["word/document.xml"] = parts.getValue("word/document.xml").decodeToString()
            .replace("<w:b/>", "<w:rFonts w:ascii=\"$family\" w:hAnsi=\"$family\"/>$properties")
            .replace("Hello {name}", "Visible text").toByteArray()
        return PdfFixture.zip(parts)
    }

    private fun failure(family: String, properties: String, mode: LoggingMode): Diagnostic {
        val output = ByteArrayOutputStream()
        val error = assertThrows(GenerationException::class.java) {
            provider().generate(GenerationRequest(source(family, properties).inputStream(), GenerationRequirements(
                FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.PDF, mapOf("profile" to "standard")))),
                output, GenerationContext(GenerationLogging(mode)))
        }
        assertEquals(0, output.size())
        return error.diagnostics.single().also {
            assertEquals(DiagnosticCodes.INVALID_REQUEST, it.code)
            assertEquals(RejectionReason.USED_FONT_FACE_UNAVAILABLE, it.rejectionReason)
        }
    }

    @Test
    fun `development identifies the requested family and all four variants with source coordinates`() {
        for ((family, properties, face) in listOf(
            Triple("Missing Sample Font", "", "regular"),
            Triple("Missing Sample Font", "<w:b/>", "bold"),
            Triple("Liberation Sans", "<w:i/>", "italic"),
            Triple("Liberation Sans", "<w:b/><w:i/>", "boldItalic"))) {
            val diagnostic = failure(family, properties, LoggingMode.DEVELOPMENT)
            assertEquals("Missing font family: $family; variant: $face", diagnostic.sourceContext?.explanation)
            assertEquals("word/document.xml", diagnostic.part)
            assertEquals("paragraph:1", diagnostic.documentLocation)
            assertTrue(requireNotNull(diagnostic.location).startsWith("xml:"))
            assertTrue(requireNotNull(diagnostic.sourceContext?.path).startsWith("/w:document[1]/w:body[1]/w:p[1]"))
        }
    }

    @Test
    fun `production does not capture the missing family or source text`() {
        val diagnostic = failure("Private Sample Font", "<w:b/>", LoggingMode.PRODUCTION)
        assertNull(diagnostic.sourceContext)
        assertNull(diagnostic.part)
        assertNull(diagnostic.documentLocation)
        assertFalse(diagnostic.message.contains("Private Sample Font"))
    }

    @Test
    fun `development font names cannot inject bidirectional formatting into details`() {
        val diagnostic = failure("Private\u202EFont", "", LoggingMode.DEVELOPMENT)
        assertFalse(requireNotNull(diagnostic.sourceContext).explanation.contains('\u202E'))
        assertTrue(diagnostic.sourceContext!!.explanation.contains("variant: regular"))
        val longName = failure("Missing" + "x".repeat(500), "", LoggingMode.DEVELOPMENT)
        val explanation = requireNotNull(longName.sourceContext).explanation
        assertTrue(explanation.length <= 256)
        assertTrue(explanation.contains("\u2026; variant: regular"))
    }
}
