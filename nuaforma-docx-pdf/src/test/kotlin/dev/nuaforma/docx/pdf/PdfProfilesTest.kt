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

    @Test
    fun `paragraph options preserve concrete type and range rejection reasons`() {
        val cases = listOf(
            "PRIVATE_TOKEN" to RejectionReason.PARAGRAPHDEFAULTS_MUST_BE_AN_OBJECT,
            emptyMap<String, Any?>() to RejectionReason.INVALID_PARAGRAPHDEFAULTS_FIELDS,
            mapOf("PRIVATE_TOKEN" to 1) to RejectionReason.INVALID_PARAGRAPHDEFAULTS_FIELDS,
            mapOf("afterTwips" to null) to RejectionReason.PARAGRAPH_DEFAULTS_REQUIRE_BOUNDED_INTEGERS,
            mapOf("afterTwips" to "PRIVATE_TOKEN") to RejectionReason.PARAGRAPH_DEFAULTS_REQUIRE_BOUNDED_INTEGERS,
            mapOf("afterTwips" to 0.5) to RejectionReason.PARAGRAPH_DEFAULTS_REQUIRE_BOUNDED_INTEGERS,
            mapOf("afterTwips" to java.math.BigInteger.TEN.pow(100)) to RejectionReason.PARAGRAPH_DEFAULTS_REQUIRE_BOUNDED_INTEGERS,
            mapOf("afterTwips" to -1) to RejectionReason.PARAGRAPH_DEFAULT_IS_OUTSIDE_ITS_SUPPORTED_RANGE,
            mapOf("afterTwips" to 2881) to RejectionReason.PARAGRAPH_DEFAULT_IS_OUTSIDE_ITS_SUPPORTED_RANGE,
            mapOf("line240ths" to 0) to RejectionReason.PARAGRAPH_DEFAULT_IS_OUTSIDE_ITS_SUPPORTED_RANGE,
            mapOf("line240ths" to 2401) to RejectionReason.PARAGRAPH_DEFAULT_IS_OUTSIDE_ITS_SUPPORTED_RANGE,
        )
        for ((raw, reason) in cases) {
            val diagnostic = assertThrows(GenerationException::class.java) {
                PdfOptions.read(mapOf("paragraphDefaults" to raw))
            }.diagnostics.single()
            assertEquals(DiagnosticCodes.UNSUPPORTED_OPTION, diagnostic.code)
            assertSame(reason, diagnostic.rejectionReason)
            assertFalse(diagnostic.message.contains("PRIVATE_TOKEN"))
        }
        for (number in listOf(0.toByte(), 0.toShort(), 0, 0L, java.math.BigInteger.ZERO)) {
            assertEquals(0, PdfOptions.read(mapOf("paragraphDefaults" to mapOf("afterTwips" to number))).paragraphDefaults!!.afterTwips)
        }
        assertEquals(2880, PdfOptions.read(mapOf("paragraphDefaults" to mapOf("afterTwips" to 2880, "line240ths" to 2400))).paragraphDefaults!!.afterTwips)
    }
}
