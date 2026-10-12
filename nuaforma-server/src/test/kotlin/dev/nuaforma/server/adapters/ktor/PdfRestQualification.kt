package dev.nuaforma.server.adapters.ktor

import dev.nuaforma.core.ProviderRegistry
import dev.nuaforma.server.ServerConfiguration
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** Explicit real-socket qualification inside the isolated PDF runtime; not a shipped entry point. */
object PdfRestQualification {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 3 && Runtime.version().feature() == 17)
        val fixtures = Path.of(args[0])
        val fonts = Path.of(args[1])
        val destination = Files.createDirectory(Path.of(args[2]))
        System.setProperty("nuaforma.pdf.launcher", "/opt/nuaforma/bin/render-pdf")
        val adapter = KtorServer(ProviderRegistry.discover(), ServerConfiguration())
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { adapter.installRoutes(this) }.start()
        try {
            val port = runBlocking { server.engine.resolvedConnectors().single().port }
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
            val base = "http://127.0.0.1:$port/api/v1"
            val caps = client.send(HttpRequest.newBuilder(URI("$base/capabilities")).GET().build(), HttpResponse.BodyHandlers.ofString())
            check(caps.statusCode() == 200 && caps.body().contains("paragraphDefaults"))
            val declarations = """[{"family":"Arial","face":"regular","resource":"arial"},{"family":"Arial","face":"bold","resource":"arialbd"},{"family":"Arial","face":"italic","resource":"ariali"},{"family":"Arial","face":"boldItalic","resource":"arialbi"},{"family":"Libre Barcode 39","face":"regular","resource":"barcode"}]"""
            fun generate(source: String, name: String, reference: String, watermark: Boolean = false) {
                val envelope = """{"input":{"format":"docx"},"output":{"format":"pdf","options":{"fontMode":"validate","fonts":$declarations,"paragraphDefaults":{"afterTwips":160,"line240ths":278}}},"data":{"customer":{"name":"Ada"},"reference":"$reference"}${if (watermark) ",\"options\":{\"watermark\":{\"text\":\"{#if true}CONCEPT{#else}FINAL{/if} — {customer.name}\"}}" else ""}}"""
                val bytes = ByteArrayOutputStream()
                fun part(id: String, type: String, value: ByteArray) {
                    bytes.write("--RESTQUAL\r\nContent-Disposition: form-data; name=\"$id\"; filename=\"ignored\"\r\nContent-Type: $type\r\n\r\n".toByteArray())
                    bytes.write(value); bytes.write("\r\n".toByteArray())
                }
                part("request", "application/json", envelope.toByteArray())
                part("template", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", Files.readAllBytes(fixtures.resolve(source)))
                listOf("arial", "arialbd", "ariali", "arialbi", "barcode").forEach { id ->
                    part("resource.$id", "application/octet-stream", Files.readAllBytes(fonts.resolve(if (id == "barcode") "LibreBarcode39-Regular.ttf" else "$id.ttf")))
                }
                bytes.write("--RESTQUAL--\r\n".toByteArray())
                val response = client.send(HttpRequest.newBuilder(URI("$base/documents")).timeout(Duration.ofMinutes(3))
                    .header("Content-Type", "multipart/form-data; boundary=RESTQUAL").POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray())).build(), HttpResponse.BodyHandlers.ofByteArray())
                check(response.statusCode() == 200) { "HTTP ${response.statusCode()}: ${response.body().decodeToString()}" }
                check(response.headers().firstValue("Content-Type").orElse("").contains("application/pdf"))
                check(response.body().take(8).toByteArray().decodeToString() == "%PDF-1.7")
                Files.write(destination.resolve("$name.pdf"), response.body())
            }
            generate("font-reference/template.docx", "tenant-a", "NF001")
            generate("font-reference/template.docx", "tenant-b", "NF002")
            generate("watermark-reference/plain.docx", "watermark-dynamic", "NF-001", true)
            check(Files.list(Path.of("/tmp")).use { paths -> paths.noneMatch {
                it.fileName.toString().startsWith("nuaforma-http-") || it.fileName.toString().startsWith("nuaforma-pdf-")
            } })
            println("Qualified real HTTP multipart DOCX-to-PDF, caller fonts, tenant references, dynamic watermark and cleanup")
        } finally { server.stop(1000, 5000) }
    }
}
