package dev.nuaforma.docx.pdf.internal

import dev.nuaforma.contracts.*
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** Owns one private operation directory; caller names never become filesystem paths. */
internal class ConversionFiles private constructor(val directory: Path) : AutoCloseable {
    val docx: Path = directory.resolve("generated.docx")
    val conversionCopy: Path = directory.resolve("input.docx")
    val pdf: Path = directory.resolve("output/input.pdf")

    override fun close() {
        // All owned processes must be stopped first: permission repair requires a quiescent private tree.
        val pending = ArrayDeque<Pair<Path, Boolean>>()
        pending.add(directory to false)
        while (pending.isNotEmpty()) {
            val (path, visited) = pending.removeLast()
            if (!visited && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.getFileStore(directory.parent).supportsFileAttributeView("posix")) {
                    // NOFOLLOW inspection excludes links; JDK's NOFOLLOW chmod cannot open a mode-000 directory.
                    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
                }
                pending.add(path to true)
                Files.newDirectoryStream(path).use { children -> for (child in children) pending.add(child to false) }
            } else Files.deleteIfExists(path)
        }
    }

    companion object {
        fun create(context: GenerationContext): ConversionFiles {
            context.checkActive()
            val directory = if (Files.getFileStore(Path.of(System.getProperty("java.io.tmpdir"))).supportsFileAttributeView("posix")) {
                Files.createTempDirectory("nuaforma-pdf-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            } else Files.createTempDirectory("nuaforma-pdf-")
            try {
                for (name in listOf("output", "fonts", "home", "cache", "profile", "tmp")) Files.createDirectory(directory.resolve(name))
                return ConversionFiles(directory)
            } catch (error: Throwable) {
                try { ConversionFiles(directory).close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                throw error
            }
        }
    }
}

internal object PdfStreams {
    fun copy(input: InputStream, output: OutputStream, maximum: Long, context: GenerationContext) {
        var total = 0L
        val buffer = ByteArray(8192)
        while (true) {
            context.checkActive()
            val count = input.read(buffer)
            if (count < 0) return
            if (count == 0) continue
            if (count > maximum - total) PdfFailure.fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, "PDF conversion byte budget was exceeded")
            output.write(buffer, 0, count)
            total += count
        }
    }

    fun publish(pdf: Path, output: OutputStream, context: GenerationContext) {
        checkFraming(pdf, context)
        Files.newInputStream(pdf).use { copy(it, output, context.limits.maxOutputBytes, context) }
    }

    fun checkFraming(pdf: Path, context: GenerationContext) {
        context.checkActive()
        if (!Files.isDirectory(pdf.parent, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(pdf, LinkOption.NOFOLLOW_LINKS)) {
            PdfFailure.fail(DiagnosticCodes.GENERATION_FAILED, "Converter did not produce a regular PDF file in its output directory")
        }
        val size = Files.size(pdf)
        if (size > context.limits.maxOutputBytes) PdfFailure.fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, "PDF output byte budget was exceeded")
        if (size < 16) PdfFailure.fail(DiagnosticCodes.GENERATION_FAILED, "Converter produced incomplete PDF output")
        // Cheap preflight before the bounded structural-validation subprocess.
        Files.newByteChannel(pdf).use { channel ->
            val header = java.nio.ByteBuffer.allocate(8)
            while (header.hasRemaining() && channel.read(header) >= 0) context.checkActive()
            if (!header.array().contentEquals("%PDF-1.7".toByteArray(Charsets.US_ASCII))) {
                PdfFailure.fail(DiagnosticCodes.GENERATION_FAILED, "Converter produced an unexpected PDF version")
            }
            channel.position(maxOf(0, size - 1024))
            val ending = java.nio.ByteBuffer.allocate(minOf(1024L, size).toInt())
            while (ending.hasRemaining() && channel.read(ending) >= 0) context.checkActive()
            if (!ending.array().toString(Charsets.US_ASCII).trimEnd().endsWith("%%EOF")) {
                PdfFailure.fail(DiagnosticCodes.GENERATION_FAILED, "Converter produced incomplete PDF output")
            }
        }
    }
}
