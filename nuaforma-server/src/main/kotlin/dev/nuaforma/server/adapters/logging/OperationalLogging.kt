package dev.nuaforma.server.adapters.logging

import dev.nuaforma.contracts.GenerationLogEvent
import dev.nuaforma.contracts.GenerationLogSink
import dev.nuaforma.contracts.GenerationLogging
import dev.nuaforma.contracts.LoggingMode
import dev.nuaforma.contracts.GenerationLogLevel
import org.slf4j.LoggerFactory
import org.slf4j.MDC

/** The only server integration with SLF4J; raw exceptions never reach the backend. */
internal object OperationalLogging : GenerationLogSink {
    private val logger = LoggerFactory.getLogger("dev.nuaforma.operational")
    override fun write(event: GenerationLogEvent) {
        val builder = when (event.level) {
            GenerationLogLevel.DEBUG -> logger.atDebug()
            GenerationLogLevel.INFO -> logger.atInfo()
            GenerationLogLevel.WARNING -> logger.atWarn()
            GenerationLogLevel.ERROR -> logger.atError()
        }
        builder.log("event={} requestId={} development={} {}", event.code, event.requestId ?: "-", event.development, event.text)
    }
    fun configured(mode: LoggingMode, requestId: String? = null) = GenerationLogging(mode, requestId, this)
    fun snapshot(): Map<String, String>? = MDC.getCopyOfContextMap()
    fun install(requestId: String): Map<String, String>? {
        val previous = snapshot()
        MDC.clear()
        MDC.put("requestId", requestId)
        return previous
    }
    fun restore(previous: Map<String, String>?) {
        if (previous == null) MDC.clear() else MDC.setContextMap(previous)
    }
    fun developmentWarning() {
        logger.warn("************************************************************\n" +
            "* DEVELOPMENT MODE — SENSITIVE DATA LOGGING ENABLED         *\n" +
            "* Document content and field values may appear in logs.    *\n" +
            "* DO NOT USE WITH PRODUCTION DATA.                         *\n" +
            "************************************************************")
    }
}
