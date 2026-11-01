package dev.nuaforma.contracts

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DocumentSourceContextTest {
    @Test fun `source excerpts are bounded secret aware immutable and retained by diagnostic copies`() {
        val source = DocumentSourceContext("Unsupported formatting", "/w:p[1]", "w:p", "x".repeat(2000))
        assertEquals(256, source.excerpt.length)
        assertEquals("[redacted expression]", DocumentSourceContext("Unsupported formatting", "/w:p[1]", "w:p", "{apiKey} PRIVATE").excerpt)
        val initial = Diagnostic(DiagnosticCodes.UNSUPPORTED_INPUT, RejectionReason.NATIVE_DOCX_PROFILE_UNSUPPORTED)
        val copy = initial.withSourceContext(source).withDocumentLocation("paragraph:1").withTemplateContext(TemplateErrorContext("{bad", 0))
        assertNull(initial.sourceContext)
        assertSame(source, copy.sourceContext)
        for ((explanation, path, construct) in listOf(
            Triple("", "/w:p[1]", "w:p"), Triple("Invalid\nreason", "/w:p[1]", "w:p"),
            Triple("x".repeat(257), "/w:p[1]", "w:p"), Triple("Unsupported", "/etc/private", "w:p"),
            Triple("Unsupported", "/w:p[1]", ""), Triple("Unsupported", "/w:p[1]", "<script>")
        )) assertThrows(IllegalArgumentException::class.java) { DocumentSourceContext(explanation, path, construct, "") }
    }
}
