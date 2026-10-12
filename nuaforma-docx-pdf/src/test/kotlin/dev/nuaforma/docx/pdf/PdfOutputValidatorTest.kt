package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.adapters.pdfbox.PdfOutputValidator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files

class PdfOutputValidatorTest {
    private fun validate(bytes: ByteArray, maximum: Long = 1024) {
        val file = Files.createTempFile("nf-pdf-validator-", ".pdf")
        try {
            Files.write(file, bytes)
            PdfOutputValidator.validate(file, maximum, GenerationContext())
        } finally { Files.delete(file) }
    }

    @Test fun `strict PDF structure accepts a static page and rejects framing-only fake output`() {
        validate(StaticPdfFixture.bytes())
        assertThrows(GenerationException::class.java) { validate("%PDF-1.7\nsynthetic\n%%EOF\n".toByteArray()) }
        val corrupted = StaticPdfFixture.bytes().decodeToString().replace("/Root 1 0 R", "/Root 99 0 R")
        assertThrows(GenerationException::class.java) { validate(corrupted.toByteArray()) }
    }

    @Test fun `active content forms external streams and attachments are rejected including orphan objects`() {
        val candidates = listOf(
            StaticPdfFixture.bytes(catalog = "/OpenAction 4 0 R", extra = listOf("<< /S /JavaScript /JS (danger) >>")),
            StaticPdfFixture.bytes(extra = listOf("<< /Type /Action /S /Launch /F (danger) >>")),
            StaticPdfFixture.bytes(catalog = "/AcroForm << /Fields [] >>"),
            StaticPdfFixture.bytes(extra = listOf("<< /Type /EmbeddedFile /Length 1 >>\nstream\nx\nendstream")),
            StaticPdfFixture.bytes(extra = listOf("<< /Length 1 /F (external) >>\nstream\nx\nendstream")),
            StaticPdfFixture.bytes(extra = listOf("<< /Type /Action /S /URI /URI (file:///secret) >>")),
        )
        for (bytes in candidates) assertThrows(GenerationException::class.java) { validate(bytes) }
        validate(StaticPdfFixture.bytes(extra = listOf("<< /Type /Action /S /URI /URI (https://example.org) >>")))
    }

    @Test fun `fonts must be embedded and declared as fonts`() {
        for (font in listOf("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>", "<< /Subtype /TrueType >>")) {
            assertThrows(GenerationException::class.java) { validate(StaticPdfFixture.bytes(
                page = "/Resources << /Font << /F1 4 0 R >> >>", extra = listOf(font))) }
        }
    }

    @Test fun `decoded stream bytes are bounded before publication`() {
        val bytes = StaticPdfFixture.bytes(page = "/Contents 4 0 R", extra = listOf("<< /Length 10 >>\nstream\n1234567890\nendstream"))
        validate(bytes, 10)
        val error = assertThrows(GenerationException::class.java) { validate(bytes, 9) }
        assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, error.diagnostics.single().code)
    }
}
