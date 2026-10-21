package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.adapters.libreoffice.BoundedProcess
import dev.nuaforma.docx.pdf.adapters.libreoffice.LibreOfficeProcesses
import dev.nuaforma.docx.pdf.adapters.pdfbox.PdfOutputValidator
import dev.nuaforma.docx.pdf.internal.ConversionFiles
import dev.nuaforma.docx.pdf.internal.PdfProfile
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DisabledConformanceTest {
    @Test fun `disabled policy always invokes real PDFBox and never requests conformance or creates an attestation`() {
        val samples = listOf(StaticPdfFixture.bytes(), "%PDF-1.7\nbad\n%%EOF\n".toByteArray(),
            StaticPdfFixture.bytes(catalog = "/OpenAction 4 0 R", extra = listOf("<< /S /JavaScript /JS (danger) >>")),
            StaticPdfFixture.bytes(page = "/Resources << /Font << /F1 4 0 R >> >>", extra = listOf("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")))
        mockkObject(BoundedProcess)
        try {
            every { BoundedProcess.run(any(), any(), any(), any(), any()) } answers {
                val command = firstArg<List<String>>()
                assertEquals("validate", command[4])
                PdfOutputValidator.validate(Path.of(command[1]).resolve("output/input.pdf"), command[5].toLong(), secondArg(), command[6])
            }
            val converter = LibreOfficeProcesses(LibreOfficeConfiguration(Path.of(System.getProperty("java.io.tmpdir"), "synthetic-launcher").toAbsolutePath(), 1, false))
            for (profile in PdfProfile.entries) for ((index, sample) in samples.withIndex()) {
                val context = GenerationContext()
                lateinit var owned: Path
                ConversionFiles.create(context).use { files ->
                    owned = files.directory
                    files.profile = profile
                    Files.write(files.pdf, sample.decodeToString().replace("%PDF-1.7", "%PDF-${profile.version}").toByteArray())
                    if (index == 0) assertDoesNotThrow { converter.validate(files, context) }
                    else assertThrows(GenerationException::class.java) { converter.validate(files, context) }
                    assertFalse(Files.exists(files.directory.resolve("conformance.txt")))
                }
                assertFalse(Files.exists(owned))
            }
            val cancelled = GenerationContext(GenerationLimits(), CancellationToken { true })
            assertThrows(GenerationException::class.java) { ConversionFiles.create(cancelled) }
            ConversionFiles.create(GenerationContext()).use { files ->
                files.profile = PdfProfile.PDFA_3B
                val error = assertThrows(GenerationException::class.java) { converter.validate(files, cancelled) }
                assertEquals(DiagnosticCodes.CANCELLED, error.diagnostics.single().code)
                assertFalse(Files.exists(files.directory.resolve("conformance.txt")))
            }
            verify(exactly = PdfProfile.entries.size * samples.size) { BoundedProcess.run(any(), any(), any(), any(), any()) }
        } finally { unmockkObject(BoundedProcess) }
    }
}
