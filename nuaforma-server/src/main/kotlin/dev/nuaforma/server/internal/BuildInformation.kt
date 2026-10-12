package dev.nuaforma.server.internal

import dev.nuaforma.contracts.ContractVersion
import java.io.InputStream
import java.util.Properties

internal object BuildInformation {
    private val metadata = checkNotNull(BuildInformation::class.java.getResourceAsStream("/META-INF/nuaforma/server-build.properties")) {
        "Server build information is missing"
    }.use(::read)
    val version: String = metadata.first
    val commit: String? = metadata.second

    internal fun read(source: InputStream): Pair<String, String?> {
        val properties = Properties().apply { load(source) }
        val version = checkNotNull(properties.getProperty("version"))
        check(version.isNotBlank() && version.length <= 128 && version.none { it.isWhitespace() })
        val identity = checkNotNull(properties.getProperty("commit"))
        val commit = if (identity == "unknown") null else identity.also {
            check(it.matches(Regex("[0-9a-f]{40}|[0-9a-f]{64}")))
        }
        return version to commit
    }

    fun response(): Map<String, Any?> = mapOf(
        "version" to version,
        "contractVersion" to ContractVersion.CURRENT.toString(),
        "commit" to commit,
    )
}
