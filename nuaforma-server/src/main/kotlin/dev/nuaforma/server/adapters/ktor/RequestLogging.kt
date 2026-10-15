package dev.nuaforma.server.adapters.ktor

import dev.nuaforma.contracts.GenerationLogging
import dev.nuaforma.server.adapters.logging.OperationalLogging
import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Saves/restores each thread's previous MDC, including nested coroutine scopes. */
internal class RequestLogging(val logging: GenerationLogging) : ThreadContextElement<Map<String, String>?>,
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RequestLogging>
    override fun updateThreadContext(context: CoroutineContext): Map<String, String>? = OperationalLogging.install(requireNotNull(logging.requestId))
    override fun restoreThreadContext(context: CoroutineContext, oldState: Map<String, String>?) = OperationalLogging.restore(oldState)
}
