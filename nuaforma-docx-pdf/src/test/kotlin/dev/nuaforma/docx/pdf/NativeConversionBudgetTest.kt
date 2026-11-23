package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.DiagnosticCodes
import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.contracts.GenerationException
import dev.nuaforma.contracts.GenerationLogging
import dev.nuaforma.contracts.LoggingMode
import dev.nuaforma.contracts.RejectionReason
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeConversionBudgetTest {
    @Test
    fun `each existing conversion limit can explain its own measured failure in development`() {
        val context = GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))
        val explanations = NativeConversionBudget.entries.map { budget ->
            assertDoesNotThrow { budget.check(context, 42, 42) }
            val failure = assertThrows(GenerationException::class.java) { budget.check(context, 43, 42) }
            val diagnostic = failure.diagnostics.single()
            assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, diagnostic.code)
            assertEquals(RejectionReason.PDF_CONVERSION_BYTE_BUDGET_WAS_EXCEEDED, diagnostic.rejectionReason)
            assertNull(diagnostic.part)
            val explanation = requireNotNull(diagnostic.sourceContext).explanation
            assertTrue(explanation.contains("required at least 43"))
            assertTrue(explanation.contains("available 42"))
            explanation
        }
        assertEquals(NativeConversionBudget.entries.size, explanations.toSet().size)
    }

    @Test
    fun `production omits measured details`() {
        val context = GenerationContext(GenerationLogging(LoggingMode.PRODUCTION))
        val failure = assertThrows(GenerationException::class.java) {
            NativeConversionBudget.SOURCE_EXPANDED_PACKAGE.check(context, 43, 42)
        }
        assertNull(failure.diagnostics.single().sourceContext)
    }
}
