package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.internal.PdfOptions
import dev.nuaforma.docx.pdf.internal.PdfProfile
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PdfProfilesTest {
    @Test
    fun `omitted profile defaults to PDF A 3b and capabilities describe the same choices`() {
        assertEquals(PdfProfile.PDFA_3B, PdfOptions.read(emptyMap()).profile)
        val choices = DocxPdfProvider().capabilities().outputOptions.getValue(DocumentFormat.PDF)
        val profile = choices.single { it.name == "profile" }
        assertEquals("pdfa-3b", profile.defaultValue)
        assertEquals(PdfProfile.entries.map { it.id }, profile.allowedValues)
        assertNull(choices.single { it.name == "version" }.defaultValue)
    }

    @Test
    fun `explicit versions must agree with the profile and missing versions are derived`() {
        for (profile in PdfProfile.entries) {
            assertEquals(profile, PdfOptions.read(mapOf("profile" to profile.id)).profile)
            assertEquals(profile, PdfOptions.read(mapOf("profile" to profile.id, "version" to profile.version)).profile)
            assertThrows(GenerationException::class.java) {
                PdfOptions.read(mapOf("profile" to profile.id, "version" to if (profile.version == "1.4") "1.7" else "1.4"))
            }
        }
    }

    @Test
    fun `unsupported profiles nulls and unknown fields never fall back to the default`() {
        for (options in listOf(mapOf("profile" to null), mapOf("profile" to "pdfa-3a"), mapOf("profile" to "PDF/A-3b"),
            mapOf("version" to null), mapOf("version" to "2.0"), mapOf("unknown" to true))) {
            assertThrows(GenerationException::class.java) { PdfOptions.read(options) }
        }
    }
}
