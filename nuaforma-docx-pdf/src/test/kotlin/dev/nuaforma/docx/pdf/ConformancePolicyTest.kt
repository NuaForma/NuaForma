package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ConformancePolicyTest {
    private val launcher = Path.of(System.getProperty("java.io.tmpdir"), "missing-policy-launcher").toAbsolutePath()

    @Test fun `discovered providers capture the property once and explicit legacy construction still enables validation`() {
        val key = "nuaforma.pdf.pdfaValidation"
        val previous = System.getProperty(key)
        try {
            System.setProperty(key, "true")
            val enabled = DocxPdfProvider()
            System.setProperty(key, "false")
            val disabled = DocxPdfProvider()
            val explicit = DocxPdfProvider(LibreOfficeConfiguration(launcher))
            System.setProperty(key, "PRIVATE_INVALID_VALUE")
            assertTrue(enabled.conformancePolicies().single().validationEnabled)
            assertFalse(disabled.conformancePolicies().single().validationEnabled)
            assertTrue(explicit.conformancePolicies().single().validationEnabled)
            assertThrows(IllegalArgumentException::class.java) { DocxPdfProvider() }
        } finally { if (previous == null) System.clearProperty(key) else System.setProperty(key, previous) }
    }

    @Test fun `old JVM constructor descriptors including Kotlin defaults retain enabled validation`() {
        assertTrue(LibreOfficeConfiguration(launcher).pdfaValidation)
        assertTrue(LibreOfficeConfiguration(launcher, 2).pdfaValidation)
        val marker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
        val oldDefault = LibreOfficeConfiguration::class.java.getConstructor(Path::class.java, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, marker)
        val configuration = oldDefault.newInstance(launcher, 0, 2, null)
        assertTrue(configuration.pdfaValidation)
        assertEquals(1, configuration.maximumParallelConversions)
        assertFalse(LibreOfficeConfiguration(launcher, 1, false).pdfaValidation)
    }

    @Test fun `concurrent explicit instances capture separate immutable policies and ignore ambient configuration`() {
        val key = "nuaforma.pdf.pdfaValidation"
        val previous = System.getProperty(key)
        System.setProperty(key, "PRIVATE_INVALID_VALUE")
        val executor = Executors.newFixedThreadPool(4)
        try {
            val providers = (0..7).map { index -> executor.submit<DocxPdfProvider> {
                DocxPdfProvider(LibreOfficeConfiguration(launcher, 1, index % 2 == 0))
            } }.map { it.get(30, TimeUnit.SECONDS) }
            providers.forEachIndexed { index, provider ->
                val policy = provider.conformancePolicies().single()
                assertEquals(index % 2 == 0, policy.validationEnabled)
                assertEquals(setOf("pdfa-1b", "pdfa-2b", "pdfa-3b"), policy.profiles)
                assertEquals(DocumentFormat.PDF, policy.outputFormat)
                assertEquals(setOf(DocumentFormat.DOCX), provider.availability().writeFormats)
                for ((profile, version) in listOf("pdfa-1b" to "1.4", "pdfa-2b" to "1.7", "pdfa-3b" to "1.7", "standard" to "1.7")) {
                    val requirements = GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                        FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile, "version" to version)))
                    assertNotEquals(AssessmentStatus.UNSUPPORTED, provider.assess(requirements).status)
                    assertEquals(AssessmentStatus.UNSUPPORTED, provider.assess(GenerationRequirements(requirements.input,
                        FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile, "version" to "9.9")))).status)
                }
                for (name in listOf("pdfaValidation", "validationEnabled", "NUAFORMA_PDFA_VALIDATION")) {
                    assertEquals(AssessmentStatus.UNSUPPORTED, provider.assess(GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                        FormatRequest(DocumentFormat.PDF, mapOf(name to false)))).status)
                    assertEquals(AssessmentStatus.UNSUPPORTED, provider.assess(GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                        FormatRequest(DocumentFormat.DOCX), GenerationOptions(extensions = mapOf(name to false)))).status)
                }
                assertThrows(UnsupportedOperationException::class.java) { (provider.conformancePolicies() as MutableList).clear() }
            }
        } finally {
            executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS)
            if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
        }
    }
}
