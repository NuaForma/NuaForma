package dev.nuaforma.pdf

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Exercise the actual isolated validator entry point; collect its own execution data when coverage is enabled. */
class ForkedValidatorCoverageTest {
    @TempDir lateinit var temporary: Path

    private fun runValidator(vararg arguments: String): Pair<Int, String> {
        val command = mutableListOf(System.getProperty("nuaforma.pdf.validation.java"), "-Xmx256m", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=2")
        System.getProperty("nuaforma.pdf.validation.coverageAgent")?.let { agent ->
            val output = Path.of(System.getProperty("nuaforma.pdf.validation.coverageDirectory"), "validator-${UUID.randomUUID()}.exec")
            command += "$agent=destfile=$output,append=false,includes=dev.nuaforma.pdf.*"
        }
        command += listOf("-Djava.awt.headless=true", "-cp", Path.of(System.getProperty("nuaforma.pdf.validation"), "*").toString(),
            "dev.nuaforma.pdf.adapters.verapdf.VeraPdfValidator")
        command += arguments
        val builder = ProcessBuilder(command).directory(temporary.toFile()).redirectErrorStream(true)
        builder.environment().clear()
        builder.environment().putAll(mapOf("HOME" to temporary.toString(), "TMPDIR" to temporary.toString(), "LANG" to "C.UTF-8", "LC_ALL" to "C.UTF-8"))
        val process = builder.start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Validator child exceeded its test deadline")
            val receipt = process.inputStream.use { it.readNBytes(16_385) }
            assertTrue(receipt.size <= 16_384, "Unbounded validator diagnostics")
            return process.exitValue() to receipt.toString(Charsets.UTF_8).trim()
        } finally {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }

    private fun fixture(profile: PdfOutputProfile): Path {
        val model = PdfDocument(PdfPage(200f, 200f, PdfMargins(16f, 16f, 16f, 16f)),
            listOf(PdfParagraph(listOf(PdfTextRun("Validator proof", PdfTextStyle("regular", 11f))))))
        val bytes = PdfEngine().render(model, FoundationLetter.fonts(), PdfOutputOptions(profile, validationEnabled = false))
        return Files.write(temporary.resolve("${profile.id}.pdf"), bytes)
    }

    private fun hash(path: Path) = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)))

    @Test fun `readiness initializes genuine vendor runtime and returns the exact receipt`() {
        assertEquals(0 to "NuaForma PDF validator ready 17", runValidator("--version"))
    }

    @Test fun `all supported profiles validate the actual final bytes in child JVMs`() {
        for (profile in PdfOutputProfile.entries) {
            val input = fixture(profile)
            val before = Files.readAllBytes(input)
            val kind = if (profile == PdfOutputProfile.STANDARD) "PDF" else "PDF/A"
            assertEquals(0 to "NuaForma $kind valid ${profile.id} ${hash(input)}", runValidator(input.toString(), profile.id))
            assertArrayEquals(before, Files.readAllBytes(input))
            assertEquals(0 to "NuaForma PDF structure valid ${profile.id} ${hash(input)}", runValidator(input.toString(), profile.id, "structure"))
        }
    }

    @Test fun `invalid protocol files and unsupported profiles fail rather than attest`() {
        val input = fixture(PdfOutputProfile.STANDARD)
        for (arguments in listOf(emptyArray(), arrayOf(input.toString()), arrayOf(input.toString(), "standard", "unknown"),
            arrayOf(input.toString(), "standard", "structure", "extra"), arrayOf(input.toString(), "pdfa-9b"),
            arrayOf(temporary.resolve("missing.pdf").toString(), "standard"))) {
            val (exit, output) = runValidator(*arguments)
            assertNotEquals(0, exit)
            assertFalse(output.startsWith("NuaForma PDF valid"))
        }
        val (exit, output) = runValidator(input.toString(), "pdfa-3b")
        assertNotEquals(0, exit, "Ordinary PDF must not be attested as archival")
        assertTrue(output.contains("PDF/A conformance validation failed"), output)
    }
}
