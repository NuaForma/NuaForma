package dev.nuaforma.contracts

import java.io.OutputStream

/** Optional, explicit development operation. Output is a diagnostic artifact, never successful generation. */
interface TemplateDiagnosticProvider {
    @Throws(GenerationException::class, java.io.IOException::class)
    fun diagnoseTemplate(request: GenerationRequest, output: OutputStream, context: GenerationContext): GenerationResult
}
