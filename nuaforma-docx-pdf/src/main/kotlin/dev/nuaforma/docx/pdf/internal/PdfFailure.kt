package dev.nuaforma.docx.pdf.internal

import dev.nuaforma.contracts.*

internal object PdfFailure {
    fun fail(code: DiagnosticCode, message: String): Nothing = throw GenerationException(listOf(Diagnostic(code, message)))
}
