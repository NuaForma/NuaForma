package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.adapters.libreoffice.BoundedProcess
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

@EnabledOnOs(OS.LINUX, disabledReason = "The qualified process runtime is Linux-only")
class BoundedProcessTest {
    @Test
    fun `trusted process receives no caller environment and readiness output is bounded`() {
        val output = Files.createTempFile("nf-probe-", ".txt")
        try {
            BoundedProcess.run(listOf("/usr/bin/env"), GenerationContext(), output)
            assertEquals("", Files.readString(output))
            val failure = assertThrows(GenerationException::class.java) {
                BoundedProcess.run(listOf("/usr/bin/head", "-c", "8192", "/dev/zero"), GenerationContext(), output)
            }
            assertEquals(DiagnosticCodes.GENERATION_FAILED, failure.diagnostics.single().code)
        } finally { Files.delete(output) }
    }

    @Test
    fun `deadline terminates a running owned process`() {
        val started = System.nanoTime()
        val failure = assertThrows(GenerationException::class.java) {
            BoundedProcess.run(listOf("/usr/bin/sleep", "60"), GenerationContext(GenerationLimits(maxDuration = Duration.ofMillis(150))))
        }
        assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, failure.diagnostics.single().code)
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(5))
    }

    @Test
    fun `cancellation while conversion runs does not become a generic failure`() {
        val started = System.nanoTime()
        val failure = assertThrows(GenerationException::class.java) {
            BoundedProcess.run(listOf("/usr/bin/sleep", "60"), GenerationContext(cancellation = CancellationToken {
                Duration.ofNanos(System.nanoTime() - started) >= Duration.ofMillis(150)
            }))
        }
        assertEquals(DiagnosticCodes.CANCELLED, failure.diagnostics.single().code)
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(5))
    }

    @Test
    fun `startup and exit failures use safe diagnostics`() {
        assertEquals(DiagnosticCodes.PROVIDER_UNAVAILABLE, assertThrows(GenerationException::class.java) {
            BoundedProcess.run(listOf("/nonexistent/nf-test-converter"), GenerationContext())
        }.diagnostics.single().code)
        val error = assertThrows(GenerationException::class.java) { BoundedProcess.run(listOf("/usr/bin/false"), GenerationContext()) }
        assertEquals(DiagnosticCodes.GENERATION_FAILED, error.diagnostics.single().code)
        assertFalse(error.diagnostics.single().message.contains("false"))
    }

    @Test
    fun `deadline actually reaps the owned process and its child`() {
        val directory = Files.createTempDirectory("nf-process-lifecycle-")
        val parentFile = directory.resolve("parent.pid")
        val childFile = directory.resolve("child.pid")
        try {
            assertThrows(GenerationException::class.java) {
                BoundedProcess.run(listOf("/bin/sh", "-c",
                    "echo \$\$ > \"\$1\"; /usr/bin/sleep 60 & echo \$! > \"\$2\"; wait", "nf-test", parentFile.toString(), childFile.toString()),
                    GenerationContext(GenerationLimits(maxDuration = Duration.ofSeconds(1))))
            }
            for (file in listOf(parentFile, childFile)) {
                val pid = Files.readString(file).trim().toLong()
                assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "Owned process $pid survived the deadline")
            }
        } finally {
            Files.deleteIfExists(parentFile); Files.deleteIfExists(childFile); Files.delete(directory)
        }
    }
}
