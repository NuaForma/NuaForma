package dev.nuaforma.contracts

import java.io.OutputStream

/** Explicit development-only DOCX inspection, independent of PDF rendering. Never generation success. */
interface DocumentDiagnosticProvider {
    @Throws(GenerationException::class, java.io.IOException::class)
    fun diagnoseDocument(request: GenerationRequest, output: OutputStream, context: GenerationContext, preview: Boolean): GenerationResult
}
