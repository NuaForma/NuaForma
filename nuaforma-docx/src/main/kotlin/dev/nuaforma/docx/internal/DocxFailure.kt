package dev.nuaforma.docx.internal

import dev.nuaforma.contracts.Diagnostic
import dev.nuaforma.contracts.DiagnosticCode
import dev.nuaforma.contracts.GenerationException

internal object DocxFailure {
    fun fail(code: DiagnosticCode, message: String): Nothing =
        throw GenerationException(listOf(Diagnostic(code, message)))
}
