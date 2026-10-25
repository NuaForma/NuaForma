package dev.nuaforma.pdf

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Trusted operator configuration. The reviewed validator distribution stays outside the host classpath. */
class PdfProcessValidator @JvmOverloads constructor(
    javaExecutable: Path, classpath: List<Path>, private val temporaryRoot: Path,
    private val timeoutMillis: Long = 30_000,
) : PdfConformanceValidator {
    private val javaBinary = javaExecutable.toAbsolutePath().normalize()
    private val libraries = classpath.map { it.toAbsolutePath().normalize() }
    init {
        require(Files.isRegularFile(javaBinary) && Files.isExecutable(javaBinary)) { "Provide a Java 17 validator executable" }
        require(libraries.size in 1..64 && libraries.all { Files.isRegularFile(it) } && libraries.sumOf { Files.size(it) } <= 134_217_728) { "Invalid validator classpath" }
        require(Files.isDirectory(temporaryRoot) && timeoutMillis in 1..120_000) { "Invalid validator limits or temporary root" }
    }

    override fun validate(bytes: ByteArray, profile: PdfOutputProfile) {
        require(profile != PdfOutputProfile.STANDARD && bytes.size in 1..16_777_216)
        cancelled()
        val directory = Files.createTempDirectory(temporaryRoot, "nuaforma-verify-")
        val input = directory.resolve("document.pdf")
        var process: Process? = null
        try {
            if (Files.getFileStore(directory).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
            Files.write(input, bytes)
            val command = listOf(javaBinary.toString(), "-Xmx256m", "-XX:MaxDirectMemorySize=64m", "-Xss512k", "-cp",
                libraries.joinToString(File.pathSeparator), "dev.nuaforma.pdf.adapters.verapdf.VeraPdfValidator", input.toAbsolutePath().toString(), profile.id)
            val builder = ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
            listOf("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS").forEach { builder.environment().remove(it) }
            process = builder.start()
            val output = ByteArrayOutputStream()
            val stream = process.inputStream
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (true) {
                cancelled()
                while (stream.available() > 0) {
                    val chunk = stream.readNBytes(minOf(stream.available(), 4096))
                    if (output.size() + chunk.size > 16_384) failed()
                    output.write(chunk)
                }
                if (!process.isAlive) break
                if (System.nanoTime() >= deadline) failed()
                process.waitFor(25, TimeUnit.MILLISECONDS)
            }
            while (stream.available() > 0) {
                val remaining = stream.readNBytes(minOf(stream.available(), 4096))
                if (output.size() + remaining.size > 16_384) failed()
                output.write(remaining)
            }
            if (output.size() > 16_384 || process.exitValue() != 0) failed()
            val receipt = "NuaForma PDF/A valid ${profile.id} ${sha256(bytes)}"
            val receipts = output.toString(Charsets.UTF_8).lineSequence().filter { it.startsWith("NuaForma PDF/A valid ") }.toList()
            if (receipts != listOf(receipt) || Files.size(input) != bytes.size.toLong() || !Files.readAllBytes(input).contentEquals(bytes)) failed()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw PdfRenderException(PdfFailureCode.CANCELLED, "PDF validation interrupted")
        } catch (failure: PdfRenderException) { throw failure }
        catch (_: Exception) { failed() }
        finally {
            // Reap on cancellation too; restore the caller's interrupt after bounded cleanup.
            var interrupted = Thread.interrupted()
            process?.let { child ->
                child.descendants().use { children -> children.forEach { it.destroyForcibly() } }
                if (child.isAlive) {
                    child.destroyForcibly()
                    val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (child.isAlive && System.nanoTime() < end) {
                        try { child.waitFor(25, TimeUnit.MILLISECONDS) }
                        catch (_: InterruptedException) { interrupted = true }
                    }
                }
                runCatching { child.inputStream.close() }
            }
            try {
                Files.deleteIfExists(input)
                Files.deleteIfExists(directory)
            } finally { if (interrupted) Thread.currentThread().interrupt() }
        }
    }

    private fun cancelled() { if (Thread.currentThread().isInterrupted) throw PdfRenderException(PdfFailureCode.CANCELLED, "PDF validation interrupted") }
    private fun failed(): Nothing = throw PdfRenderException(PdfFailureCode.VALIDATION_FAILED, "Independent PDF conformance validation failed")
    private fun sha256(bytes: ByteArray) = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
