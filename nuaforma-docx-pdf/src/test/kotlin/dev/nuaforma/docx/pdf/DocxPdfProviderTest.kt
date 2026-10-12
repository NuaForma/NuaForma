package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.DocxProvider
import dev.nuaforma.docx.pdf.adapters.libreoffice.LibreOfficeProcesses
import dev.nuaforma.docx.pdf.internal.ConversionFiles
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class DocxPdfProviderTest {
    private fun requirements(format: DocumentFormat, options: Map<String, Any?> = emptyMap()) =
        GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(format, options))

    @Test
    fun `missing converter keeps DOCX output available and PDF unsupported requests distinct`() {
        val absent = java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).resolve("nf-absent-${java.util.UUID.randomUUID()}").toAbsolutePath()
        val provider = DocxPdfProvider(LibreOfficeConfiguration(absent))
        assertEquals(setOf(DocumentFormat.DOCX, DocumentFormat.PDF), provider.capabilities().writeFormats)
        assertEquals(setOf(DocumentFormat.DOCX), provider.availability().writeFormats)
        assertEquals(AssessmentStatus.SUPPORTED, provider.assess(requirements(DocumentFormat.DOCX)).status)
        assertEquals(AssessmentStatus.UNAVAILABLE, provider.assess(requirements(DocumentFormat.PDF)).status)
        assertEquals(AssessmentStatus.UNAVAILABLE, provider.assess(requirements(DocumentFormat.PDF, mapOf("profile" to "pdfa-3b"))).status)
        assertEquals(AssessmentStatus.UNSUPPORTED, provider.assess(requirements(DocumentFormat.PDF, mapOf("profile" to "pdfa-3a"))).status)
        val request = GenerationRequest(PdfFixture.zip(PdfFixture.parts()).inputStream(), requirements(DocumentFormat.DOCX), mapOf("name" to "Ada"))
        val output = ByteArrayOutputStream()
        provider.generate(request, output, GenerationContext())
        val direct = ByteArrayOutputStream()
        DocxProvider().generate(GenerationRequest(PdfFixture.zip(PdfFixture.parts()).inputStream(), requirements(DocumentFormat.DOCX), mapOf("name" to "Ada")), direct, GenerationContext())
        val actual = PdfFixture.unzip(output.toByteArray()); val expected = PdfFixture.unzip(direct.toByteArray())
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (name, bytes) -> assertArrayEquals(bytes, actual.getValue(name), name) }
    }

    @Test
    fun `PDF orchestration delegates transformation once and cleans up converter files`() {
        mockkConstructor(LibreOfficeProcesses::class)
        var operation: java.nio.file.Path? = null
        try {
            every { anyConstructed<LibreOfficeProcesses>().readiness() } returns CapabilityAssessment(AssessmentStatus.SUPPORTED)
            every { anyConstructed<LibreOfficeProcesses>().verify(any()) } just Runs
            every { anyConstructed<LibreOfficeProcesses>().fontFamilies(any(), any()) } returns setOf("DejaVu Sans")
            every { anyConstructed<LibreOfficeProcesses>().validate(any(), any()) } just Runs
            every { anyConstructed<LibreOfficeProcesses>().convert(any(), any()) } answers {
                val files = firstArg<ConversionFiles>()
                operation = files.directory
                val document = PdfFixture.unzip(Files.readAllBytes(files.conversionCopy)).getValue("word/document.xml").decodeToString()
                assertTrue(document.contains("Ada")); assertFalse(document.contains("{name}"))
                Files.write(files.pdf, StaticPdfFixture.bytes())
            }
            val output = object : ByteArrayOutputStream() { var closed = false; override fun close() { closed = true } }
            val result = DocxPdfProvider().generate(GenerationRequest(PdfFixture.zip(PdfFixture.parts()).inputStream(),
                requirements(DocumentFormat.PDF), mapOf("name" to "Ada")), output, GenerationContext())
            assertEquals("1.7", result.output.options["version"])
            assertFalse(output.closed)
            assertFalse(Files.exists(operation!!))
            verify(exactly = 1) { anyConstructed<LibreOfficeProcesses>().convert(any(), any()) }
        } finally { unmockkConstructor(LibreOfficeProcesses::class) }
    }
}
