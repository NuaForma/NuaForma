package dev.nuaforma.server

import dev.nuaforma.contracts.GenerationLimits
import dev.nuaforma.contracts.LoggingMode
import java.nio.file.Path
import java.time.Duration

class ServerConfiguration @JvmOverloads constructor(
    val host: String = "127.0.0.1",
    val port: Int = 8080,
    val temporaryDirectory: Path = Path.of(System.getProperty("java.io.tmpdir")),
    val maximumConcurrentRequests: Int = 4,
    val maximumRequestBytes: Long = 100L * 1024 * 1024,
    val maximumJsonBytes: Long = 1024 * 1024,
    val maximumResourceBytes: Long = 16L * 1024 * 1024,
    val maximumResources: Int = 32,
    val requestTimeout: Duration = Duration.ofMinutes(3),
    val generationLimits: GenerationLimits = GenerationLimits(),
) {
    var loggingMode: LoggingMode = LoggingMode.PRODUCTION
        private set

    constructor(base: ServerConfiguration, loggingMode: LoggingMode) : this(base.host, base.port, base.temporaryDirectory,
        base.maximumConcurrentRequests, base.maximumRequestBytes, base.maximumJsonBytes, base.maximumResourceBytes,
        base.maximumResources, base.requestTimeout, base.generationLimits) { this.loggingMode = loggingMode }

    init {
        require(host.isNotBlank() && port in 0..65535)
        require(maximumConcurrentRequests in 1..64 && maximumResources in 0..128)
        require(maximumRequestBytes in 1..1024L * 1024 * 1024)
        require(maximumJsonBytes in 1..16L * 1024 * 1024 && maximumResourceBytes in 1..maximumRequestBytes)
        require(!requestTimeout.isNegative && !requestTimeout.isZero && requestTimeout <= Duration.ofHours(1))
    }
}
