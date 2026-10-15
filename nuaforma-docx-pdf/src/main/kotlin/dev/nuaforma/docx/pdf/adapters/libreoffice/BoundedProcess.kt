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
        val phase = command.getOrNull(4)?.takeIf { it in setOf("probe", "fonts", "catalog", "convert", "convert-pdfa-1b", "convert-pdfa-2b", "convert-pdfa-3b",
            "validate", "validate-pdfa-1b", "validate-pdfa-2b", "validate-pdfa-3b") } ?: "external"
        context.logging.operation("SANDBOX_PROCESS_START", mapOf("operation" to phase))
        operationDirectory?.let { TemporaryBudget.check(it, context) }
        val builder = ProcessBuilder(command)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .redirectOutput(probeOutput?.let { ProcessBuilder.Redirect.to(it.toFile()) } ?: ProcessBuilder.Redirect.DISCARD)
        builder.environment().clear()
        val process = try { builder.start() } catch (error: java.io.IOException) {
            context.logging.failure("SANDBOX_PROCESS_START_FAILED", error)
            PdfFailure.fail(DiagnosticCodes.PROVIDER_UNAVAILABLE, "Isolated PDF converter could not be started")
        }
        val captured = java.io.ByteArrayOutputStream(4096)
        val stderr = Thread({
            try {
                val buffer = ByteArray(4096)
                while (true) {
                    val count = process.errorStream.read(buffer)
                    if (count < 0) break
                    val remaining = 4096 - captured.size()
                    if (remaining > 0) captured.write(buffer, 0, minOf(count, remaining))
                }
            } catch (_: java.io.IOException) { /* Process cleanup closes the bounded diagnostic channel. */ }
        }, "nuaforma-sandbox-diagnostics").apply { isDaemon = true; start() }
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
            if (process.exitValue() != 0) {
                stderr.join(250)
                val reason = classify(captured.toString(Charsets.UTF_8))
                context.logging.operation("SANDBOX_PROCESS_FAILED", mapOf("operation" to phase, "exitCode" to process.exitValue().toString(), "reason" to reason.first, "action" to reason.second))
                context.logging.trace("SANDBOX_FAILURE_DETAILS", mapOf("exitCode" to process.exitValue(), "reason" to reason.first,
                    "capturedBytes" to captured.size(), "captureLimitBytes" to 4096, "rawOutput" to "[withheld; may contain document or credential data]"))
                PdfFailure.fail(DiagnosticCodes.GENERATION_FAILED, "Isolated PDF converter failed")
            }
            context.logging.operation("SANDBOX_PROCESS_COMPLETED", mapOf("operation" to phase))
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
            try { stderr.join(250) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
    }

    private fun classify(text: String): Pair<String, String> {
        val safe = text.lowercase(java.util.Locale.ROOT)
        return when {
            "creating new namespace failed" in safe || "user namespaces" in safe -> "USER_NAMESPACE_DENIED" to "Check native architecture, kernel user-namespace support and the reviewed container isolation policy"
            "devpts" in safe || "mount" in safe && "/dev/pts" in safe -> "DEVICE_MOUNT_DENIED" to "Check the qualified device-mount launcher and host SELinux/AppArmor policy"
            "operation not permitted" in safe || "permission denied" in safe -> "OS_PERMISSION_DENIED" to "Check executable permissions and the reviewed host seccomp/SELinux/AppArmor policy"
            "no such file or directory" in safe -> "RUNTIME_FILE_MISSING" to "Check that the qualified renderer and validator runtime is installed completely"
            "cannot allocate memory" in safe || "outofmemoryerror" in safe -> "PROCESS_MEMORY_LIMIT" to "Check the documented container and validator memory limits"
            else -> "UNCLASSIFIED_PROCESS_FAILURE" to "Check qualified renderer/validator installation and isolation readiness; raw child output is withheld"
        }
    }
}
