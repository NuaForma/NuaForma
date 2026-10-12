package dev.nuaforma.docx.pdf.adapters.libreoffice

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.internal.PdfFailure
import dev.nuaforma.docx.pdf.internal.TemporaryBudget
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Trusted command only. The sandbox owns descendant containment; this layer owns lifecycle. */
internal object BoundedProcess {
    fun run(command: List<String>, context: GenerationContext, probeOutput: Path? = null, maximumProbeBytes: Long = 4096, operationDirectory: Path? = null) {
        require(command.isNotEmpty() && Path.of(command.first()).isAbsolute)
        context.checkActive()
        operationDirectory?.let { TemporaryBudget.check(it, context) }
        val builder = ProcessBuilder(command)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectOutput(probeOutput?.let { ProcessBuilder.Redirect.to(it.toFile()) } ?: ProcessBuilder.Redirect.DISCARD)
        builder.environment().clear()
        val process = try { builder.start() } catch (_: java.io.IOException) {
            PdfFailure.fail(DiagnosticCodes.PROVIDER_UNAVAILABLE, "Isolated PDF converter could not be started")
        }
        try {
            process.outputStream.close()
            var nextBudgetCheck = System.nanoTime()
            while (true) {
                context.checkActive()
                if (operationDirectory != null && System.nanoTime() >= nextBudgetCheck) {
                    TemporaryBudget.check(operationDirectory, context)
                    nextBudgetCheck = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250)
                }
                if (probeOutput != null && Files.exists(probeOutput) && Files.size(probeOutput) > maximumProbeBytes) {
                    PdfFailure.fail(DiagnosticCodes.GENERATION_FAILED, "Converter readiness response exceeded its limit")
                }
                if (process.waitFor(50, TimeUnit.MILLISECONDS)) break
            }
            context.checkActive()
            operationDirectory?.let { TemporaryBudget.check(it, context) }
            if (process.exitValue() != 0) PdfFailure.fail(DiagnosticCodes.GENERATION_FAILED, "Isolated PDF converter failed")
            if (probeOutput != null && Files.size(probeOutput) > maximumProbeBytes) {
                PdfFailure.fail(DiagnosticCodes.GENERATION_FAILED, "Converter readiness response exceeded its limit")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            PdfFailure.fail(DiagnosticCodes.CANCELLED, "PDF conversion was interrupted")
        } finally {
            // Killing Bubblewrap's namespace supervisor also terminates remaining sandbox processes.
            val children = process.descendants().use { it.toList() }.sortedByDescending { child ->
                generateSequence(child) { it.parent().orElse(null) }.take(128).count()
            }
            // Stop leaves first; keep supervisors alive briefly to reap their terminated children.
            val reapDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500)
            for (child in children) {
                if (child.isAlive) child.destroyForcibly()
                try { child.onExit().get(maxOf(1, reapDeadline - System.nanoTime()), TimeUnit.NANOSECONDS) }
                catch (_: java.util.concurrent.TimeoutException) { }
                catch (_: java.util.concurrent.ExecutionException) { }
                catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
            if (process.isAlive) process.destroyForcibly()
            try { process.waitFor(5, TimeUnit.SECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            process.inputStream.close()
            process.errorStream.close()
        }
    }
}
