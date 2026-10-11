package dev.nuaforma.contracts

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class ContextTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `default budgets are finite and deadlines cannot extend the duration budget`() {
        val context = GenerationContext(clock = clock)
        assertEquals(now.plusSeconds(120), context.deadline)
        context.checkActive()
        assertEquals(now.plusSeconds(5), GenerationContext(deadline = now.plusSeconds(5), clock = clock).deadline)
        assertEquals(now.plusSeconds(120), GenerationContext(deadline = now.plusSeconds(600), clock = clock).deadline)
        assertThrows(IllegalArgumentException::class.java) { GenerationLimits(maxTemplateBytes = 0) }
        assertThrows(IllegalArgumentException::class.java) { GenerationLimits(maxRepetitions = -1) }
        assertThrows(IllegalArgumentException::class.java) { GenerationLimits(maxDuration = Duration.ZERO) }
        assertThrows(IllegalArgumentException::class.java) { GenerationLimits(maxDuration = Duration.ofDays(2)) }
    }

    @Test
    fun `cancellation and reached deadlines return structured errors`() {
        val cancelled = GenerationContext(cancellation = CancellationToken { true }, clock = clock)
        val cancellation = assertThrows(GenerationException::class.java) { cancelled.checkActive() }
        assertEquals(DiagnosticCodes.CANCELLED, cancellation.diagnostics.single().code)
        val expired = GenerationContext(deadline = now, clock = clock)
        val expiration = assertThrows(GenerationException::class.java) { expired.checkActive() }
        assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, expiration.diagnostics.single().code)
        assertEquals("deadline", expiration.diagnostics.single().location)
    }

    @Test
    fun `thread interruption is honored without clearing the interrupted flag`() {
        try {
            Thread.currentThread().interrupt()
            val error = assertThrows(GenerationException::class.java) { GenerationContext(clock = clock).checkActive() }
            assertEquals(DiagnosticCodes.CANCELLED, error.diagnostics.single().code)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }
}
