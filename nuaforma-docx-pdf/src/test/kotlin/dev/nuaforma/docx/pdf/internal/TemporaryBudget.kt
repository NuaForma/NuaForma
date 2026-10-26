package dev.nuaforma.docx.pdf.internal

import dev.nuaforma.contracts.*
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributeView

/** Sampled private-work budget supplements, but does not replace, the deployment's hard tmpfs limit. */
internal object TemporaryBudget {
    fun check(directory: Path, context: GenerationContext) {
        var entries = 0
        var bytes = 0L
        try {
            Files.newDirectoryStream(directory).use { root ->
                if (root !is SecureDirectoryStream<Path>) PdfFailure.fail(DiagnosticCodes.PROVIDER_UNAVAILABLE,
                    RejectionReason.PDF_RUNTIME_REQUIRES_SECURE_PRIVATE_DIRECTORY_TRAVERSAL)
                fun visit(stream: SecureDirectoryStream<Path>, depth: Int) {
                    if (depth > 64) exceeded()
                    context.checkActive()
                    if (++entries > 16_384) exceeded()
                    for (entry in stream) {
                        context.checkActive()
                        val name = entry.fileName
                        try {
                            val attributes = stream.getFileAttributeView(name, BasicFileAttributeView::class.java,
                                LinkOption.NOFOLLOW_LINKS).readAttributes()
                            if (attributes.isDirectory) {
                                // Relative descriptor traversal prevents a directory-to-link replacement race.
                                stream.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS).use { visit(it, depth + 1) }
                            } else {
                                if (++entries > 16_384) exceeded()
                                if (!attributes.isRegularFile) PdfFailure.fail(DiagnosticCodes.GENERATION_FAILED,
                                    RejectionReason.CONVERTER_CREATED_AN_UNSUPPORTED_TEMPORARY_FILESYSTEM_ENTRY)
                                if (attributes.size() > 512L * 1024 * 1024 - bytes) exceeded()
                                bytes += attributes.size()
                            }
                        } catch (_: NoSuchFileException) { /* Private cache entries may disappear during conversion. */ }
                    }
                }
                visit(root, 0)
            }
        } catch (_: java.io.IOException) {
            PdfFailure.fail(DiagnosticCodes.GENERATION_FAILED, RejectionReason.CONVERTER_TEMPORARY_FILES_COULD_NOT_BE_INSPECTED_SAFELY)
        }
    }
    private fun exceeded(): Nothing = PdfFailure.fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, RejectionReason.PDF_PRIVATE_TEMPORARY_STORAGE_BUDGET_WAS_EXCEEDED)
}
