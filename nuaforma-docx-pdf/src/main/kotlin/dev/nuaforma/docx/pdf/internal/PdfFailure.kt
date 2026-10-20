package dev.nuaforma.docx.pdf.internal

import dev.nuaforma.contracts.*

internal object PdfFailure {
    fun fail(code: DiagnosticCode, reason: RejectionReason): Nothing = throw GenerationException(listOf(Diagnostic(code, reason)))
}
