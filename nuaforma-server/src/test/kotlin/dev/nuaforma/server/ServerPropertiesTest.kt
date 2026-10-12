package dev.nuaforma.server

import dev.nuaforma.server.internal.ServerProperties
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Properties

class ServerPropertiesTest {
    @TempDir lateinit var temporary: Path
    private fun properties(vararg values: Pair<String, String>) = Properties().apply {
        setProperty("java.io.tmpdir", temporary.toString())
        values.forEach { (key, value) -> setProperty("nuaforma.server.$key", value) }
    }

    @Test fun `defaults remain bounded and loopback`() {
        val config = ServerProperties.read(properties())
        assertEquals("127.0.0.1", config.host)
        assertEquals(8080, config.port)
        assertEquals(4, config.maximumConcurrentRequests)
        assertEquals(temporary, config.temporaryDirectory)
        assertEquals(120000, config.generationLimits.maxDuration.toMillis())
        assertEquals(0, ServerProperties.read(properties("port" to "0")).port)
    }

    @Test fun `operator properties map to independent HTTP and engine limits`() {
        val config = ServerProperties.read(properties("host" to "0.0.0.0", "port" to "9090", "concurrency" to "2",
            "requestBytes" to "100", "resourceBytes" to "20", "resources" to "1", "jsonBytes" to "30",
            "templateBytes" to "40", "outputBytes" to "50", "expandedBytes" to "60", "archiveEntries" to "7",
            "expressionDepth" to "8", "repetitions" to "9", "timeoutMillis" to "1000", "generationMillis" to "500"))
        assertEquals(9090, config.port)
        assertEquals(2, config.maximumConcurrentRequests)
        assertEquals(100, config.maximumRequestBytes)
        assertEquals(20, config.maximumResourceBytes)
        assertEquals(1, config.maximumResources)
        assertEquals(30, config.maximumJsonBytes)
        assertEquals(40, config.generationLimits.maxTemplateBytes)
        assertEquals(50, config.generationLimits.maxOutputBytes)
        assertEquals(60, config.generationLimits.maxExpandedBytes)
        assertEquals(7, config.generationLimits.maxArchiveEntries)
        assertEquals(8, config.generationLimits.maxExpressionDepth)
        assertEquals(9, config.generationLimits.maxRepetitions)
        assertEquals(1000, config.requestTimeout.toMillis())
        assertEquals(500, config.generationLimits.maxDuration.toMillis())
    }

    @Test fun `invalid values fail without echoing operator input`() {
        listOf("port" to "-1", "port" to "65536", "concurrency" to "65", "jsonBytes" to "-1",
            "generationMillis" to "3600001", "outputBytes" to "PRIVATE", "repetitions" to "9223372036854775808")
            .forEach { (key, value) ->
                val error = assertThrows(IllegalArgumentException::class.java) { ServerProperties.read(properties(key to value)) }
                assertFalse(error.message.orEmpty().contains(value))
            }
        assertThrows(IllegalArgumentException::class.java) { ServerProperties.read(properties().apply {
            setProperty("java.io.tmpdir", "relative")
        }) }
        val pathError = assertThrows(IllegalArgumentException::class.java) { ServerProperties.read(properties().apply {
            setProperty("java.io.tmpdir", "PRIVATE\u0000")
        }) }
        assertFalse(pathError.message.orEmpty().contains("PRIVATE"))
        assertThrows(IllegalArgumentException::class.java) { ServerProperties.read(properties("requestBytes" to "10", "resourceBytes" to "11")) }
    }
}
