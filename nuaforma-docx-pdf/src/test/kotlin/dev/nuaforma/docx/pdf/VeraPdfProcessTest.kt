package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.adapters.libreoffice.LibreOfficeProcesses
import dev.nuaforma.docx.pdf.internal.ConversionFiles
import dev.nuaforma.docx.pdf.internal.PdfProfile
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

@EnabledOnOs(OS.LINUX)
class VeraPdfProcessTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `zero exit without a complete matching profile and byte proof is rejected`() {
        for ((index, response) in listOf("", "NuaForma PDF/A valid pdfa-3b ${"0".repeat(64)}",
            "NuaForma PDF/A valid pdfa-2b ${"0".repeat(64)}", "true").withIndex()) {
            val launcher = temporary.resolve("validator-$index.sh")
            Files.writeString(launcher, "#!/bin/sh\nset -eu\ncase \"\u0024{4}\" in validate) exit 0 ;; validate-pdfa-3b) printf '%s\\n' '$response' ;; *) exit 64 ;; esac\n")
            Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwx------"))
            ConversionFiles.create(GenerationContext()).use { files ->
                files.profile = PdfProfile.PDFA_3B
                Files.write(files.pdf, StaticPdfFixture.bytes())
                val failure = assertThrows(GenerationException::class.java) {
                    LibreOfficeProcesses(LibreOfficeConfiguration(launcher)).validate(files, GenerationContext())
                }
                assertEquals(DiagnosticCodes.GENERATION_FAILED, failure.diagnostics.single().code)
            }
        }
    }
}
