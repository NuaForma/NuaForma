package dev.nuaforma.server

import com.fasterxml.jackson.databind.ObjectMapper
import dev.nuaforma.core.ProviderRegistry
import dev.nuaforma.server.adapters.ktor.KtorServer
import dev.nuaforma.server.internal.BuildInformation
import dev.nuaforma.server.internal.ShippedSbom
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class SbomTest {
    @TempDir lateinit var temporary: Path

    @Test fun `download is a bounded static production inventory with safe headers and source identity`() = testApplication {
        application { KtorServer(ProviderRegistry.registered(emptyList()), ServerConfiguration()).installRoutes(this) }
        val response = client.get("/api/v1/sbom")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("application/vnd.cyclonedx+json", response.headers[HttpHeaders.ContentType]?.substringBefore(';'))
        assertEquals("attachment; filename=\"nuaforma-sbom.json\"", response.headers[HttpHeaders.ContentDisposition])
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertNotNull(response.headers["X-Request-ID"])
        val bytes = response.bodyAsBytes()
        assertArrayEquals(ShippedSbom.packaged().bytes, bytes)
        val tree = ObjectMapper().readTree(bytes)
        assertEquals("CycloneDX", tree.path("bomFormat").asText()); assertEquals("1.7", tree.path("specVersion").asText())
        assertEquals(BuildInformation.version, tree.path("metadata").path("component").path("version").asText())
        val components = tree.path("components")
        assertTrue(components.any { it.path("name").asText() == "docx4j-core" })
        assertTrue(components.none { it.path("group").asText() in setOf("org.junit.jupiter", "io.mockk", "com.tngtech.archunit", "org.cyclonedx") })
        assertTrue(components.none { it.path("name").asText() == "nuaforma-playground" })
        assertTrue(components.all { it.path("purl").asText().startsWith("pkg:maven/") && it.path("hashes").first().path("alg").asText() == "SHA-256" })
        val content = response.bodyAsText()
        assertFalse(content.contains("/mnt/")); assertFalse(content.contains("file:")); assertFalse(content.contains("project_path"))
        assertEquals(response.bodyAsText(), client.get("/api/v1/sbom").bodyAsText())
    }

    @Test fun `configured inventory is snapshotted defensively rather than read during requests`() {
        val path = temporary.resolve("sbom.json")
        val original = ShippedSbom.packaged().bytes
        Files.write(path, original)
        val document = ShippedSbom.configured(path.toString())
        Files.writeString(path, "changed after startup")
        document.bytes[0] = 0
        assertArrayEquals(original, document.bytes)
        assertEquals("\"" + MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) } + "\"", document.etag)
        assertThrows(IllegalArgumentException::class.java) { ShippedSbom.configured("relative-path.json") }
        assertThrows(IllegalArgumentException::class.java) { ShippedSbom.read(byteArrayOf().inputStream()) }
        assertThrows(IllegalArgumentException::class.java) { ShippedSbom.read(ByteArray(16 * 1024 * 1024 + 1).inputStream()) }
    }

    @Test fun `inventory covers every resolved production artifact with its actual current hash`() {
        val mapper = ObjectMapper()
        val artifacts = mapper.readTree(Files.readString(Path.of(System.getProperty("nuaforma.sbomRuntimeManifest"))))
        val components = mapper.readTree(ShippedSbom.packaged().bytes).path("components")
        assertEquals(artifacts.size(), components.size())
        for (artifact in artifacts) {
            val component = components.single { it.path("group") == artifact.path("group") && it.path("name") == artifact.path("name") && it.path("version") == artifact.path("version") }
            val bytes = Files.readAllBytes(Path.of(artifact.path("file").asText()))
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(hash, component.path("hashes").first().path("content").asText(), artifact.path("name").asText())
        }
    }
}
