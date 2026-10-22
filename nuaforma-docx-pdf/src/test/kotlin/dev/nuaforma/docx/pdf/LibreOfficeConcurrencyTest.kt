package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.adapters.libreoffice.BoundedProcess
import dev.nuaforma.docx.pdf.adapters.libreoffice.LibreOfficeProcesses
import dev.nuaforma.docx.pdf.internal.ConversionFiles
import dev.nuaforma.docx.pdf.internal.RuntimeVerification
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.*
import java.util.concurrent.atomic.*

@EnabledOnOs(OS.LINUX, disabledReason = "Isolated process integration is Linux-only")
class LibreOfficeConcurrencyTest {
    @TempDir lateinit var directory: Path
    private fun launcher(): Path = directory.resolve("launcher").also {
        Files.writeString(it, "synthetic launcher identity")
        assertTrue(it.toFile().setExecutable(true, true))
    }
    private val version = "LibreOffice 26.8.1.1 bf6ef2662401c7d6dce671ab4deb26ee0af3a7c1\nNuaForma PDF validator 1\nNuaForma PDF/A validator 1\n"

    @Test
    fun `warm runtime stays ready during conversion and expired probe waits within caller deadline`() {
        val now = AtomicLong(0)
        val runtime = LibreOfficeProcesses(LibreOfficeConfiguration(launcher()), RuntimeVerification(Duration.ofSeconds(30), now::get))
        val probes = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        mockkObject(BoundedProcess)
        try {
            every { BoundedProcess.run(any(), any(), any(), any(), any()) } answers {
                when (firstArg<List<String>>()[4]) {
                    "probe" -> { probes.incrementAndGet(); Files.writeString(thirdArg<Path>(), version) }
                    "convert" -> { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }
                    else -> fail<Unit>("Unexpected synthetic operation")
                }
            }
            runtime.verify(GenerationContext())
            ConversionFiles.create(GenerationContext()).use { files ->
                val conversion = executor.submit { runtime.convert(files, GenerationContext()) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertEquals(AssessmentStatus.SUPPORTED, runtime.readiness().status)
                repeat(3) { runtime.verify(GenerationContext()) }
                assertEquals(1, probes.get(), "Warm requests must not queue extra probes behind conversion")
                now.set(Duration.ofSeconds(30).toNanos())
                val failure = assertThrows(GenerationException::class.java) {
                    runtime.verify(GenerationContext(GenerationLimits(maxDuration = Duration.ofMillis(150))))
                }
                assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, failure.diagnostics.single().code)
                assertEquals(1, probes.get())
                release.countDown()
                conversion.get(5, TimeUnit.SECONDS)
                runtime.verify(GenerationContext())
                assertEquals(2, probes.get())
            }
        } finally {
            release.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            unmockkObject(BoundedProcess)
        }
    }

    @Test
    fun `launcher changes and failed identity checks invalidate success and recover without stale fallback`() {
        val launcher = launcher()
        val runtime = LibreOfficeProcesses(LibreOfficeConfiguration(launcher))
        val probes = AtomicInteger()
        val response = AtomicReference(version)
        mockkObject(BoundedProcess)
        try {
            every { BoundedProcess.run(any(), any(), any(), any(), any()) } answers {
                probes.incrementAndGet()
                Files.writeString(thirdArg<Path>(), response.get())
            }
            repeat(3) { runtime.verify(GenerationContext()) }
            assertEquals(1, probes.get())
            Files.writeString(launcher, "changed launcher identity of different size")
            response.set("unqualified runtime\n")
            repeat(2) {
                assertEquals(DiagnosticCodes.PROVIDER_UNAVAILABLE, assertThrows(GenerationException::class.java) {
                    runtime.verify(GenerationContext())
                }.diagnostics.single().code)
            }
            assertEquals(3, probes.get(), "Failed verification must never reuse the earlier success")
            response.set(version)
            runtime.verify(GenerationContext())
            assertEquals(4, probes.get())
            assertTrue(launcher.toFile().setExecutable(false, false))
            assertEquals(AssessmentStatus.UNAVAILABLE, runtime.readiness().status)
            assertTrue(launcher.toFile().setExecutable(true, true))
            runtime.verify(GenerationContext())
            assertEquals(5, probes.get())
        } finally { unmockkObject(BoundedProcess) }
    }

    @Test
    fun `process ceiling includes conversion and validation and cancelled waiters never release a permit`() {
        for (maximum in listOf(1, 2)) {
            val runtime = LibreOfficeProcesses(LibreOfficeConfiguration(launcher(), maximum))
            val active = AtomicInteger()
            val peak = AtomicInteger()
            val started = CountDownLatch(maximum)
            val release = CountDownLatch(1)
            val cancelled = AtomicBoolean(false)
            val executor = Executors.newFixedThreadPool(maximum + 1)
            mockkObject(BoundedProcess)
            try {
                every { BoundedProcess.run(any(), any(), any(), any(), any()) } answers {
                    val count = active.incrementAndGet()
                    peak.accumulateAndGet(count, ::maxOf)
                    started.countDown()
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)) }
                    finally { active.decrementAndGet() }
                }
                val files = (0..maximum).map { ConversionFiles.create(GenerationContext()) }
                try {
                    val owners = (0 until maximum).map { index -> executor.submit {
                        if (index % 2 == 0) runtime.convert(files[index], GenerationContext())
                        else runtime.validate(files[index], GenerationContext())
                    } }
                    assertTrue(started.await(5, TimeUnit.SECONDS))
                    val waiter = executor.submit {
                        runtime.convert(files.last(), GenerationContext(cancellation = CancellationToken(cancelled::get)))
                    }
                    cancelled.set(true)
                    val error = assertThrows(ExecutionException::class.java) { waiter.get(5, TimeUnit.SECONDS) }
                    assertEquals(DiagnosticCodes.CANCELLED, (error.cause as GenerationException).diagnostics.single().code)
                    assertEquals(maximum, active.get())
                    release.countDown()
                    owners.forEach { it.get(5, TimeUnit.SECONDS) }
                    runtime.convert(files.last(), GenerationContext())
                    assertEquals(maximum, peak.get())
                    assertEquals(0, active.get())
                } finally { files.forEach { it.close() } }
            } finally {
                release.countDown()
                executor.shutdownNow()
                executor.awaitTermination(5, TimeUnit.SECONDS)
                unmockkObject(BoundedProcess)
            }
        }
    }
}
