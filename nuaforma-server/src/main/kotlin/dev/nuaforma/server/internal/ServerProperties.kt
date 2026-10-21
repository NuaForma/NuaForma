package dev.nuaforma.server.internal

import dev.nuaforma.contracts.GenerationLimits
import dev.nuaforma.contracts.LoggingMode
import dev.nuaforma.server.ServerConfiguration
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Duration
import java.util.Properties

/** Operator configuration only; never populated from HTTP input. */
internal object ServerProperties {
    fun pdfaValidation(value: String?): Boolean = when (value) {
        null, "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("Invalid NUAFORMA_PDFA_VALIDATION; expected true or false")
    }
    fun playground(value: String?, mode: LoggingMode, installed: Boolean): Boolean {
        require(value == null || value in setOf("true", "false")) { "Invalid NUAFORMA_PLAYGROUND" }
        val enabled = value == "true"
        require(!enabled || mode == LoggingMode.DEVELOPMENT) { "Playground requires development mode" }
        require(!enabled || installed) { "Playground module is not installed" }
        return enabled
    }
    fun mode(value: String?): LoggingMode = when (value) {
        null, "production" -> LoggingMode.PRODUCTION
        "development" -> LoggingMode.DEVELOPMENT
        else -> throw IllegalArgumentException("Invalid NUAFORMA_MODE; expected production or development")
    }
    fun read(properties: Properties): ServerConfiguration {
        fun text(name: String, default: String) = properties.getProperty("nuaforma.server.$name", default)
        fun number(name: String, default: Long, range: LongRange): Long {
            val value = text(name, default.toString()).toLongOrNull()
            require(value != null && value in range) { "Invalid nuaforma.server.$name" }
            return value
        }
        val temporary = try { Path.of(properties.getProperty("java.io.tmpdir", System.getProperty("java.io.tmpdir"))) }
            catch (_: InvalidPathException) { throw IllegalArgumentException("Invalid temporary directory") }
        require(temporary.isAbsolute && Files.isDirectory(temporary) && Files.isWritable(temporary)) {
            "A writable absolute temporary directory is required"
        }
        val defaults = GenerationLimits()
        return ServerConfiguration(
            host = text("host", "127.0.0.1"),
            port = number("port", 8080, 0..65535L).toInt(),
            temporaryDirectory = temporary,
            maximumConcurrentRequests = number("concurrency", 4, 1..64L).toInt(),
            maximumRequestBytes = number("requestBytes", 100L * 1024 * 1024, 1..1024L * 1024 * 1024),
            maximumJsonBytes = number("jsonBytes", 1024 * 1024, 1..16L * 1024 * 1024),
            maximumResourceBytes = number("resourceBytes", 16L * 1024 * 1024, 1..1024L * 1024 * 1024),
            maximumResources = number("resources", 32, 0..128L).toInt(),
            requestTimeout = Duration.ofMillis(number("timeoutMillis", 180000, 1..3600000L)),
            generationLimits = GenerationLimits(
                maxTemplateBytes = number("templateBytes", defaults.maxTemplateBytes, 1..1024L * 1024 * 1024),
                maxOutputBytes = number("outputBytes", defaults.maxOutputBytes, 1..1024L * 1024 * 1024),
                maxExpandedBytes = number("expandedBytes", defaults.maxExpandedBytes, 1..1024L * 1024 * 1024),
                maxArchiveEntries = number("archiveEntries", defaults.maxArchiveEntries.toLong(), 1..100000L).toInt(),
                maxExpressionDepth = number("expressionDepth", defaults.maxExpressionDepth.toLong(), 1..256L).toInt(),
                maxRepetitions = number("repetitions", defaults.maxRepetitions, 1..1000000L),
                maxDuration = Duration.ofMillis(number("generationMillis", defaults.maxDuration.toMillis(), 1..3600000L)),
            ),
        )
    }
}
