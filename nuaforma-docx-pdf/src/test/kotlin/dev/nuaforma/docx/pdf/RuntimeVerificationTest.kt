package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.internal.RuntimeVerification
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class RuntimeVerificationTest {
    @Test
    fun `only successful fresh matching runtime identities are reused and failures recover`() {
        val time = AtomicLong(0)
        val verification = RuntimeVerification(Duration.ofSeconds(30), time::get)
        val checks = AtomicInteger()
        fun check(identity: String = "runtime") = verification.ensure(identity, GenerationContext()) { checks.incrementAndGet() }
        repeat(3) { check() }
        assertEquals(1, checks.get())
        time.set(Duration.ofSeconds(30).toNanos())
        assertThrows(IllegalStateException::class.java) {
            verification.ensure("runtime", GenerationContext()) { throw IllegalStateException("Synthetic probe failure") }
        }
        check()
        assertEquals(2, checks.get())
        check("changed")
        assertEquals(3, checks.get())
        verification.invalidate()
        check("changed")
        assertEquals(4, checks.get())
    }

    @Test
    fun `concurrent cold and expired checks are single flight and waiter deadline does not poison success`() {
        val time = AtomicLong(0)
        val verification = RuntimeVerification(Duration.ofSeconds(30), time::get)
        val checks = AtomicInteger()
        val executor = Executors.newFixedThreadPool(8)
        try {
            repeat(2) {
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val owner = executor.submit {
                    verification.ensure("runtime", GenerationContext()) {
                        checks.incrementAndGet()
                        entered.countDown()
                        assertTrue(release.await(5, TimeUnit.SECONDS))
                    }
                }
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    val timeout = assertThrows(GenerationException::class.java) {
                        verification.ensure("runtime", GenerationContext(GenerationLimits(maxDuration = Duration.ofMillis(100)))) {
                            fail<Unit>("A waiting caller must not start another probe")
                        }
                    }
                    assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, timeout.diagnostics.single().code)
                    val waiters = (1..6).map {
                        executor.submit { verification.ensure("runtime", GenerationContext()) { checks.incrementAndGet() } }
                    }
                    release.countDown()
                    owner.get(5, TimeUnit.SECONDS)
                    waiters.forEach { it.get(5, TimeUnit.SECONDS) }
                    assertEquals(it + 1, checks.get())
                } finally { release.countDown() }
                time.addAndGet(Duration.ofSeconds(30).toNanos())
            }
        } finally { executor.shutdownNow() }
    }

    @Test
    fun `warm verification still honors cancellation and deadline`() {
        val verification = RuntimeVerification()
        verification.ensure("runtime", GenerationContext()) {}
        val error = assertThrows(GenerationException::class.java) {
            verification.ensure("runtime", GenerationContext(cancellation = CancellationToken { true })) { fail<Unit>() }
        }
        assertEquals(DiagnosticCodes.CANCELLED, error.diagnostics.single().code)
        val expired = GenerationContext(deadline = java.time.Instant.EPOCH)
        assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, assertThrows(GenerationException::class.java) {
            verification.ensure("runtime", expired) { fail<Unit>() }
        }.diagnostics.single().code)
    }
}
