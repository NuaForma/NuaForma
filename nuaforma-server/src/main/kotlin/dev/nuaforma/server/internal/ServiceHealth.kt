package dev.nuaforma.server.internal

import dev.nuaforma.contracts.*
import dev.nuaforma.core.ProviderRegistry
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Health reads never invoke providers; one cooperative background observation supplies readiness. */
internal class ServiceHealth(private val registry: ProviderRegistry,
                             private val logging: GenerationLogging = GenerationLogging.NONE,
                             private val refreshMillis: Long = 30_000,
                             private val maximumAgeMillis: Long = 60_000,
                             private val nanoTime: () -> Long = System::nanoTime) : AutoCloseable {
    private data class Observation(val ready: Boolean, val time: Long)
    @Volatile private var running = false
    @Volatile private var draining = false
    @Volatile private var stopped = false
    @Volatile private var observation: Observation? = null
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "nuaforma-readiness").apply { isDaemon = true }
    }

    init { require(refreshMillis > 0 && maximumAgeMillis > refreshMillis) }
    fun live(): Boolean = !stopped
    fun ready(): Boolean {
        val value = observation ?: return false
        return running && !draining && value.ready && nanoTime() - value.time <= TimeUnit.MILLISECONDS.toNanos(maximumAgeMillis)
    }

    @Synchronized fun start() {
        if (running || stopped || draining) return
        running = true
        worker.scheduleWithFixedDelay(::observe, 0, refreshMillis, TimeUnit.MILLISECONDS)
    }

    private fun observe() {
        if (draining || stopped) return
        val context = GenerationContext(GenerationLimits(maxDuration = Duration.ofSeconds(5)),
            CancellationToken { draining || stopped }, null, java.time.Clock.systemUTC(), logging)
        val ready = try {
            registry.providers.any { provider ->
                context.checkActive()
                try {
                    val availability = registry.availability(provider, context)
                    context.checkActive()
                    availability.readFormats.isNotEmpty() && availability.writeFormats.isNotEmpty()
                } catch (error: Exception) {
                    context.checkActive()
                    logging.failure("PROVIDER_READINESS_FAILED", error, mapOf("provider" to provider.metadata.id))
                    false
                } catch (error: LinkageError) {
                    logging.failure("PROVIDER_READINESS_FAILED", error, mapOf("provider" to provider.metadata.id))
                    false
                } catch (error: java.util.ServiceConfigurationError) {
                    logging.failure("PROVIDER_READINESS_FAILED", error, mapOf("provider" to provider.metadata.id))
                    false
                }
            }
        } catch (error: Exception) {
            if (!draining && !stopped) logging.failure("READINESS_OBSERVATION_FAILED", error)
            false
        } catch (error: LinkageError) {
            logging.failure("READINESS_OBSERVATION_FAILED", error)
            false
        } catch (error: java.util.ServiceConfigurationError) {
            logging.failure("READINESS_OBSERVATION_FAILED", error)
            false
        }
        if (draining || stopped) return
        val previous = observation
        observation = Observation(ready, nanoTime())
        if (previous == null || previous.ready != ready) logging.operation("SERVICE_READINESS_CHANGED", mapOf("ready" to ready.toString()))
    }

    fun drain() { draining = true }
    override fun close() { drain(); stopped = true; worker.shutdownNow() }
}
