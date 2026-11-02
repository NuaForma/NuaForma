package dev.nuaforma.server

import dev.nuaforma.contracts.*
import dev.nuaforma.core.*
import dev.nuaforma.docx.pdf.*
import dev.nuaforma.server.adapters.ktor.KtorServer
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class TemplateCleanupHttpTest {
    @TempDir lateinit var temporary: Path

    private fun registry(): ProviderRegistry = ProviderRegistry.registered(listOf(ProviderRegistration(ProviderMetadata("nuaforma-docx-pdf",
        DocxPdfProvider::class.java.name, "0.4.0-alpha.1", ContractVersion.CURRENT)) { DocxPdfProvider(NativePdfConfiguration(emptyList())) }))

    private fun web() = WebApplication(listOf(WebApplication.Asset("/playground/", "text/html", "<!doctype html>Playground fixture".toByteArray())))

    private fun unpack(bytes: ByteArray): Map<String, ByteArray> = linkedMapOf<String, ByteArray>().also { parts ->
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                parts[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
    }

    private fun template(): ByteArray {
        val source = Path.of(System.getProperty("nuaforma.repositoryRoot")).resolve("nuaforma-docx-pdf/src/test/resources/native-profile/word-letter.docx")
        val parts = unpack(Files.readAllBytes(source)).toMutableMap()
        parts["docProps/thumbnail.jpeg"] = byteArrayOf(1, 2, 3)
        parts["word/document.xml"] = parts.getValue("word/document.xml").decodeToString()
            .replace("Geachte", "{#if x} {#each rows} {customer.name | trim()} {unclosed Geachte").toByteArray()
        return ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip -> parts.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry()
            } }
        }.toByteArray()
    }

    private fun multipart(source: ByteArray, extra: String = ""): ByteArray = ByteArrayOutputStream().also { out ->
        out.write(("--NF191\r\nContent-Disposition: form-data; name=\"template\"; filename=\"original.docx\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n").toByteArray())
        out.write(source)
        out.write("\r\n$extra--NF191--\r\n".toByteArray())
    }.toByteArray()

    @Test
    fun `actual Playground cleanup returns separate template and report without data evaluation or font dependencies`() = testApplication {
        val events = java.util.Collections.synchronizedList(mutableListOf<GenerationLogEvent>())
        application { KtorServer(registry(), ServerConfiguration(ServerConfiguration(temporaryDirectory = temporary), LoggingMode.DEVELOPMENT), events::add, web()).installRoutes(this) }
        val original = template()
        val snapshot = original.copyOf()
        val response = client.post(WebApplication.TEMPLATE_CLEANUP_PATH) {
            contentType(ContentType.MultiPart.FormData.withParameter("boundary", "NF191")); setBody(multipart(original))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals("native-structure", response.headers["NuaForma-Template-Cleanup"])
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().startsWith("application/zip"))
        val archive = unpack(response.bodyAsBytes())
        assertEquals(setOf("cleaned-template.docx", "cleanup-report.txt"), archive.keys)
        val cleaned = unpack(archive.getValue("cleaned-template.docx"))
        assertFalse(cleaned.containsKey("docProps/thumbnail.jpeg"))
        assertArrayEquals(unpack(original).getValue("word/document.xml"), cleaned.getValue("word/document.xml"))
        val report = archive.getValue("cleanup-report.txt").decodeToString()
        assertTrue(report.contains("docProps/thumbnail.jpeg"))
        assertTrue(report.contains("YOU decide"))
        assertFalse(report.contains("{customer.name"))
        assertArrayEquals(snapshot, original)
        val id = response.headers["X-Request-ID"]
        assertNotNull(id)
        assertTrue(synchronized(events) { events.any { it.code == "TEMPLATE_CLEANUP_COMPLETED" && it.requestId == id } })
        Files.list(temporary).use { assertEquals(0L, it.count()) }
        val openApi = client.get("/openapi.json")
        assertEquals(HttpStatusCode.OK, openApi.status)
        assertFalse(openApi.bodyAsText().contains(WebApplication.TEMPLATE_CLEANUP_PATH))
    }

    @Test
    fun `production and development without Playground never expose the cleanup route`() {
        for (mode in LoggingMode.entries) testApplication {
            application { KtorServer(registry(), ServerConfiguration(ServerConfiguration(temporaryDirectory = temporary), mode)).installRoutes(this) }
            assertEquals(HttpStatusCode.NotFound, client.post(WebApplication.TEMPLATE_CLEANUP_PATH).status)
        }
    }

    @Test
    fun `cleanup refuses data and resource parts and cleans every temporary file on failure`() = testApplication {
        application { KtorServer(registry(), ServerConfiguration(ServerConfiguration(temporaryDirectory = temporary), LoggingMode.DEVELOPMENT), webApplication = web()).installRoutes(this) }
        for (name in listOf("request", "resource.private")) {
            val extra = "--NF191\r\nContent-Disposition: form-data; name=\"$name\"\r\nContent-Type: application/json\r\n\r\n{}\r\n"
            val response = client.post(WebApplication.TEMPLATE_CLEANUP_PATH) {
                contentType(ContentType.MultiPart.FormData.withParameter("boundary", "NF191")); setBody(multipart(template(), extra))
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
            Files.list(temporary).use { assertEquals(0L, it.count()) }
        }
    }
}
