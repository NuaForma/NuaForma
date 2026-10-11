package dev.nuaforma.docx.internal

import dev.nuaforma.contracts.GenerationContext
import java.nio.file.Files
import java.nio.file.Path

/** An operation owns one private directory; archive paths never become host paths. */
internal class TemporaryPackage private constructor(private val directory: Path, val path: Path) : AutoCloseable {
    override fun close() {
        Files.deleteIfExists(path)
        Files.deleteIfExists(directory)
    }

    companion object {
        fun create(context: GenerationContext): TemporaryPackage {
            context.checkActive()
            val directory = Files.createTempDirectory("nuaforma-docx-")
            try {
                context.checkActive()
                return TemporaryPackage(directory, Files.createTempFile(directory, "package-", ".zip"))
            } catch (error: Throwable) {
                try { Files.deleteIfExists(directory) } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                throw error
            }
        }
    }
}
