package dev.nuaforma.server

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

class LoggingStartupTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `real server prints development warning before serving and production stays safe`() {
        for (mode in listOf(null, "production", "development")) for (policy in listOf(null, "true", "false")) {
            val port = ServerSocket(0).use { it.localPort }
            val output = ByteArrayOutputStream()
            val builder = ProcessBuilder(java(), "-Dnuaforma.server.port=$port", "-Dnuaforma.pdf.validator.launcher=/missing-logging-fixture",
                "-Djava.io.tmpdir=$temporary", "-cp", classpath(), Main::class.java.name).redirectErrorStream(true)
            builder.environment().clear()
            if (mode != null) builder.environment()["NUAFORMA_MODE"] = mode
            if (policy != null) builder.environment()["NUAFORMA_PDFA_VALIDATION"] = policy
            val process = builder.start()
            val reader = drain(process, output)
            try {
                val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                var response: HttpResponse<String>? = null
                while (response == null && System.nanoTime() < deadline && process.isAlive) {
                    try { response = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/v1/version")).timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString()) }
                    catch (_: java.io.IOException) { Thread.sleep(50) }
                }
                assertNotNull(response, output.toString(Charsets.UTF_8))
                assertEquals(200, response!!.statusCode())
                assertTrue(response.headers().firstValue("X-Request-ID").isPresent)
            } finally { close(process, reader) }
            val logs = output.toString(Charsets.UTF_8)
            assertEquals(mode == "development", logs.contains("DEVELOPMENT MODE — SENSITIVE DATA LOGGING ENABLED"))
            assertEquals(policy == "false", logs.contains("PDF/A CONFORMANCE VALIDATION DISABLED"))
            assertEquals(policy == "false", logs.contains("PDF/A conformance is not checked by NuaForma"))
            if (policy == "false") assertTrue(logs.indexOf("CONFORMANCE VALIDATION DISABLED") < logs.indexOf("REQUEST_STARTED"))
            assertTrue(logs.contains("NATIVE_PDF_VALIDATOR_UNAVAILABLE")); assertTrue(logs.contains("PROVIDER_READINESS"))
            assertTrue(logs.contains("requestId=-")); assertFalse(logs.contains("/missing-logging-fixture"))
            if (mode == "development") assertTrue(logs.indexOf("DEVELOPMENT MODE") < logs.indexOf("REQUEST_STARTED"))
        }
    }

    @Test fun `invalid conformance policy rejects both real entry points before binding without leaking configuration`() {
        for (entry in listOf(Main::class.java.name, "dev.nuaforma.playground.Main")) {
            for (value in listOf("", " ", "TRUE", "FALSE", "0", "1", "flase", "false ", "PRIVATE_TOKEN\nsecret")) {
                val port = ServerSocket(0).use { it.localPort }
                val output = ByteArrayOutputStream()
                val builder = ProcessBuilder(java(), "-Dnuaforma.server.port=$port", "-cp", classpath(), entry).redirectErrorStream(true)
                builder.environment().clear(); builder.environment()["NUAFORMA_PDFA_VALIDATION"] = value
                val process = builder.start()
                val reader = drain(process, output)
                try { assertTrue(process.waitFor(15, TimeUnit.SECONDS)); assertNotEquals(0, process.exitValue()) }
                finally { close(process, reader) }
                val logs = output.toString(Charsets.UTF_8)
                assertTrue(logs.contains("SERVER_STARTUP_FAILED"), logs)
                assertFalse(logs.contains("PRIVATE_TOKEN") || logs.contains("secret"))
                assertFalse(logs.contains("REQUEST_STARTED") || logs.contains("PROVIDER_ACTIVATED"))
                ServerSocket(port).use { assertTrue(it.isBound) }
            }
        }
    }

    @Test
    fun `invalid and explicitly empty modes exit unsuccessfully without echoing values`() {
        for (mode in listOf("", "PRIVATE_MODE_VALUE")) {
            val output = ByteArrayOutputStream()
            val builder = ProcessBuilder(java(), "-cp", classpath(), Main::class.java.name).redirectErrorStream(true)
            builder.environment().clear(); builder.environment()["NUAFORMA_MODE"] = mode
            val process = builder.start()
            val reader = drain(process, output)
            try { assertTrue(process.waitFor(15, TimeUnit.SECONDS)); assertNotEquals(0, process.exitValue()) }
            finally { close(process, reader) }
            val logs = output.toString(Charsets.UTF_8)
            assertTrue(logs.contains("SERVER_STARTUP_FAILED")); assertTrue(logs.contains("IllegalArgumentException"))
            assertFalse(logs.contains("PRIVATE_MODE_VALUE")); assertFalse(logs.contains("DEVELOPMENT MODE"))
        }
    }

    private fun java() = Path.of(System.getProperty("java.home"), "bin", "java").toString()
    private fun classpath(): String = requireNotNull(System.getProperty("nuaforma.loggingTestClasspath"))
    private fun drain(process: Process, output: ByteArrayOutputStream) = Thread {
        try {
            val buffer = ByteArray(4096)
            while (true) {
                val count = process.inputStream.read(buffer)
                if (count < 0) break
                if (output.size() < 262144) output.write(buffer, 0, minOf(count, 262144 - output.size()))
            }
        } catch (_: java.io.IOException) { }
    }.apply { isDaemon = true; start() }
    private fun close(process: Process, reader: Thread) {
        process.destroy()
        if (!process.waitFor(6, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS) }
        reader.join(1000); process.inputStream.close()
    }
}
