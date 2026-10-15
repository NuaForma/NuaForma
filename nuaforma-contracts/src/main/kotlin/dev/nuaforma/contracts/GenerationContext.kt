package dev.nuaforma.contracts

import java.time.Clock
import java.time.Duration
import java.time.Instant

fun interface CancellationToken {
    fun isCancellationRequested(): Boolean

    companion object {
        @JvmField val NONE = CancellationToken { false }
    }
}

/** Processing budgets, not permission to access additional data or host resources. */
class GenerationLimits @JvmOverloads constructor(
    val maxTemplateBytes: Long = 32L * 1024 * 1024,
    val maxOutputBytes: Long = 128L * 1024 * 1024,
    val maxExpandedBytes: Long = 256L * 1024 * 1024,
    val maxArchiveEntries: Int = 10_000,
    val maxExpressionDepth: Int = 64,
    val maxRepetitions: Long = 100_000,
    val maxDuration: Duration = Duration.ofMinutes(2),
) {
    init {
        require(maxTemplateBytes > 0 && maxOutputBytes > 0 && maxExpandedBytes > 0) { "Byte limits must be positive" }
        require(maxArchiveEntries > 0 && maxExpressionDepth > 0 && maxRepetitions > 0) {
            "Entry, depth and repetition limits must be positive"
        }
        require(!maxDuration.isZero && !maxDuration.isNegative && maxDuration <= Duration.ofDays(1)) {
            "Duration must be positive and no more than one day"
        }
    }
}

/** Providers enforce budgets and regularly check cancellation, including delegated conversion. */
class GenerationContext @JvmOverloads constructor(
    val limits: GenerationLimits = GenerationLimits(),
    val cancellation: CancellationToken = CancellationToken.NONE,
    deadline: Instant? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    val deadline: Instant = minOf(deadline ?: Instant.MAX, clock.instant().plus(limits.maxDuration))
    var logging: GenerationLogging = GenerationLogging.NONE
        private set

    constructor(limits: GenerationLimits, cancellation: CancellationToken, deadline: Instant?, clock: Clock, logging: GenerationLogging) :
        this(limits, cancellation, deadline, clock) { this.logging = logging }

    constructor(logging: GenerationLogging) : this() { this.logging = logging }

    @Throws(GenerationException::class)
    fun checkActive() {
        if (cancellation.isCancellationRequested() || Thread.currentThread().isInterrupted) {
            throw GenerationException(listOf(Diagnostic(DiagnosticCodes.CANCELLED, "Generation was cancelled")))
        }
        if (!clock.instant().isBefore(deadline)) {
            throw GenerationException(listOf(Diagnostic(
                DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, "Generation deadline was reached", location = "deadline",
            )))
        }
    }
}
