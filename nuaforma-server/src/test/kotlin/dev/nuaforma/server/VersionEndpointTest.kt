package dev.nuaforma.server

import com.fasterxml.jackson.databind.ObjectMapper
import dev.nuaforma.contracts.ContractVersion
import dev.nuaforma.core.ProviderRegistry
import dev.nuaforma.server.adapters.ktor.KtorServer
import dev.nuaforma.server.internal.ApiRoutes
import dev.nuaforma.server.internal.BuildInformation
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.mockk.mockk
import io.mockk.Called
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

class VersionEndpointTest {
    @TempDir lateinit var temporary: Path
    private val mapper = ObjectMapper()

    @Test
    fun `version returns packaged build identity without consulting providers and matches OpenAPI`() = testApplication {
        val registry = mockk<ProviderRegistry>()
        application { KtorServer(registry, ServerConfiguration(temporaryDirectory = temporary)).installRoutes(this) }
        val response = client.get(ApiRoutes.VERSION)
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers[HttpHeaders.ContentType]!!.startsWith("application/json"))
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        val value = mapper.readTree(response.bodyAsText())
        assertEquals(setOf("version", "contractVersion", "commit"), value.fieldNames().asSequence().toSet())
        assertEquals(System.getProperty("nuaforma.expectedBuildVersion"), value.path("version").asText())
        assertEquals(ContractVersion.CURRENT.toString(), value.path("contractVersion").asText())
        val commit = System.getProperty("nuaforma.expectedBuildCommit")
        if (commit == "unknown") assertTrue(value.path("commit").isNull)
        else assertEquals(commit, value.path("commit").asText())
        assertEquals(response.bodyAsText(), client.get(ApiRoutes.VERSION).bodyAsText())
        val contract = mapper.readTree(client.get(ApiRoutes.OPENAPI_JSON).bodyAsText())
        assertEquals(value.path("version").asText(), contract.path("info").path("version").asText())
        assertTrue(OpenApiSchemaChecks(contract).matches("BuildVersion", value))
        assertEquals("#/components/schemas/BuildVersion", contract.path("paths").path(ApiRoutes.VERSION)
            .path("get").path("responses").path("200").path("content").path("application/json").path("schema").path("\$ref").asText())
        verify { registry wasNot Called }
        Files.list(temporary).use { assertEquals(0, it.count()) }
    }

    @Test
    fun `generated metadata is packaged in the server JAR`() {
        ZipFile(System.getProperty("nuaforma.serverJar")).use { jar ->
            val entry = requireNotNull(jar.getEntry("META-INF/nuaforma/server-build.properties"))
            jar.getInputStream(entry).use { source ->
                assertEquals(BuildInformation.version to BuildInformation.commit, BuildInformation.read(source))
            }
        }
    }

    @Test
    fun `build metadata accepts source archive identity and rejects missing or malformed values`() {
        fun read(text: String) = text.byteInputStream().use(BuildInformation::read)
        assertEquals("0.1.0-SNAPSHOT" to null, read("version=0.1.0-SNAPSHOT\ncommit=unknown\n"))
        val commit = "a".repeat(40)
        assertEquals("0.1.0" to commit, read("version=0.1.0\ncommit=$commit\n"))
        for (invalid in listOf("", "version=0.1.0\n", "version=\ncommit=unknown\n", "version=0.1.0\ncommit=/private/path\n")) {
            assertThrows(IllegalStateException::class.java) { read(invalid) }
        }
    }
}
