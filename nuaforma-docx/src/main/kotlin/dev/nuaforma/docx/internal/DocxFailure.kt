package dev.nuaforma.docx.internal

import dev.nuaforma.contracts.RejectionReason
import dev.nuaforma.contracts.Diagnostic
import dev.nuaforma.contracts.DiagnosticCode
import dev.nuaforma.contracts.GenerationException

internal object DocxFailure {
    fun fail(code: DiagnosticCode, reason: RejectionReason): Nothing =
        throw GenerationException(listOf(Diagnostic(code, reason)))

    fun <T> inPart(part: String, action: () -> T): T = try { action() } catch (error: GenerationException) {
        throw GenerationException(error.diagnostics.map { diagnostic ->
            diagnostic.rejectionReason?.let { Diagnostic(diagnostic.code, it, diagnostic.severity, diagnostic.location, diagnostic.part ?: part) } ?: diagnostic
        })
    }
}
