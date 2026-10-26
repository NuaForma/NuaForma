package dev.nuaforma.docx.pdf.internal

import dev.nuaforma.contracts.RejectionReason
import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.contracts.GenerationException
import dev.nuaforma.contracts.Diagnostic
import dev.nuaforma.contracts.DiagnosticCodes
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** Only successful, request-independent runtime identity checks may be reused. */
internal class RuntimeVerification(
    private val maximumAge: Duration = Duration.ofSeconds(30),
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private data class Verified(val identity: Any, val at: Long)
    @Volatile private var verified: Verified? = null
    private val lock = ReentrantLock(true)

    init { require(!maximumAge.isNegative && !maximumAge.isZero) }

    fun invalidate() { verified = null }

    fun ensure(identity: Any, context: GenerationContext, operation: () -> Unit) {
        context.checkActive()
        if (fresh(identity)) return
        var acquired = false
        try {
            while (!acquired) {
                context.checkActive()
                acquired = lock.tryLock(50, TimeUnit.MILLISECONDS)
            }
            context.checkActive()
            if (fresh(identity)) return
            verified = null
            operation()
            context.checkActive()
            verified = Verified(identity, nanoTime())
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw GenerationException(listOf(Diagnostic(DiagnosticCodes.CANCELLED, RejectionReason.PDF_RUNTIME_VERIFICATION_WAS_CANCELLED)))
        } finally {
            if (acquired) lock.unlock()
        }
    }

    private fun fresh(identity: Any): Boolean {
        val snapshot = verified ?: return false
        val age = nanoTime() - snapshot.at
        return snapshot.identity == identity && age >= 0 && age < maximumAge.toNanos()
    }
}
