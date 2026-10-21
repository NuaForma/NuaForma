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

    @Test fun `enabled conformance process failure deadline and cancellation never bypass validation`() {
        for (mode in listOf("failure", "deadline", "cancel")) {
            val marker = temporary.resolve("started-$mode")
            val launcher = temporary.resolve("fail-$mode.sh")
            val action = if (mode == "failure") "exit 8" else "printf started > '$marker'; /bin/sleep 30"
            Files.writeString(launcher, "#!/bin/sh\ncase \"\u0024{4}\" in validate) exit 0 ;; validate-pdfa-3b) $action ;; *) exit 64 ;; esac\n")
            Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwx------"))
            val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
            val context = GenerationContext(GenerationLimits(maxDuration = java.time.Duration.ofMillis(if (mode == "deadline") 200 else 10000)), CancellationToken { cancelled.get() })
            val observer = if (mode == "cancel") Thread {
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
                while (!Files.exists(marker) && System.nanoTime() < deadline) Thread.sleep(10)
                cancelled.set(true)
            }.apply { start() } else null
            try {
                ConversionFiles.create(context).use { files ->
                    files.profile = PdfProfile.PDFA_3B
                    Files.write(files.pdf, StaticPdfFixture.bytes())
                    val error = assertThrows(GenerationException::class.java) {
                        LibreOfficeProcesses(LibreOfficeConfiguration(launcher)).validate(files, context)
                    }
                    assertEquals(when (mode) { "failure" -> DiagnosticCodes.GENERATION_FAILED; "deadline" -> DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED; else -> DiagnosticCodes.CANCELLED }, error.diagnostics.single().code)
                    assertFalse(Files.exists(files.directory.resolve("conformance.txt")) && Files.size(files.directory.resolve("conformance.txt")) > 0)
                }
            } finally { observer?.join(6000) }
        }
    }

    @Test
    fun `runtime warnings cannot be mistaken for an exact conformance proof`() {
        val pdf = StaticPdfFixture.bytes()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(pdf)
        val proof = "NuaForma PDF/A valid pdfa-3b ${java.util.HexFormat.of().formatHex(digest)}"
        for ((index, response) in listOf(proof, "[warning] GC thread allocation failed\n$proof", "$proof\n[warning] GC thread allocation failed").withIndex()) {
            val launcher = temporary.resolve("proof-$index.sh")
            Files.writeString(launcher, "#!/bin/sh\nset -eu\ncase \"\u0024{4}\" in validate) exit 0 ;; validate-pdfa-3b) printf '%s\\n' '$response' ;; *) exit 64 ;; esac\n")
            Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwx------"))
            ConversionFiles.create(GenerationContext()).use { files ->
                files.profile = PdfProfile.PDFA_3B
                Files.write(files.pdf, pdf)
                val converter = LibreOfficeProcesses(LibreOfficeConfiguration(launcher))
                if (index == 0) assertDoesNotThrow { converter.validate(files, GenerationContext()) }
                else {
                    val failure = assertThrows(GenerationException::class.java) { converter.validate(files, GenerationContext()) }
                    assertEquals(DiagnosticCodes.GENERATION_FAILED, failure.diagnostics.single().code)
                }
            }
        }
    }

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
