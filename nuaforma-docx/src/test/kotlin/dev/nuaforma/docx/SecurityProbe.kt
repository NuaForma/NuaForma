package dev.nuaforma.docx

import dev.nuaforma.contracts.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Fresh-process probe keeps temporary storage and default logging independent of the test worker. */
internal object SecurityProbe {
    const val SECRET = "NF_PRIVATE_CANARY_639025"
    private val scratch = Path.of(System.getProperty("java.io.tmpdir"))
    private val provider = DocxProvider()
    private val requirements = GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.DOCX))
    private val data = mapOf("customer" to mapOf("name" to SECRET), "reference" to SECRET)

    @JvmStatic
    fun main(arguments: Array<String>) {
        check(arguments.isEmpty())
        check(Runtime.version().feature() == 17)
        val original = SyntheticDocx.parts()
        val bytes = SyntheticDocx.zip(original)
        val seen = mutableSetOf<Path>()

        fun temporaryFiles(): List<Path> = Files.list(scratch).use { folders -> folders.toList().flatMap { folder ->
            check(Files.isDirectory(folder) && folder.fileName.toString().startsWith("nuaforma-docx-"))
            if (Files.getFileStore(folder).supportsFileAttributeView("posix")) {
                check(Files.getPosixFilePermissions(folder) == setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE))
            }
            Files.list(folder).use { it.toList() }.also { files ->
                check(files.size == 1 && Files.isRegularFile(files.single()))
                if (Files.getFileStore(folder).supportsFileAttributeView("posix")) check(Files.getPosixFilePermissions(files.single()) == setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
            }
        } }
        fun clean() { Files.list(scratch).use { check(it.count() == 0L) { "Temporary storage was not cleaned" } } }
        fun reject(parts: Map<String, ByteArray>, code: DiagnosticCode, context: GenerationContext = GenerationContext(), requestData: Map<String, *> = data) {
            val output = ByteArrayOutputStream()
            val input = object : ByteArrayInputStream(SyntheticDocx.zip(parts)) { override fun close() { error("Caller input was closed") } }
            try { provider.generate(GenerationRequest(input, requirements, requestData), output, context); error("Unsafe input was accepted") }
            catch (error: GenerationException) {
                check(error.diagnostics.any { it.code == code }) { "Unexpected diagnostic code" }
                check(error.cause == null && error.diagnostics.none { SECRET in it.message })
                check(output.size() == 0) { "Failed generation published bytes" }
            }
            clean()
        }

        // Observe private scratch permissions during both input and final output, not after cleanup.
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                seen.addAll(temporaryFiles())
                return super.read(buffer, offset, length)
            }
            override fun close() { error("Caller input was closed") }
        }
        val output = object : ByteArrayOutputStream() {
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                seen.addAll(temporaryFiles())
                super.write(buffer, offset, length)
            }
            override fun close() { error("Caller output was closed") }
        }
        provider.generate(GenerationRequest(input, requirements, data), output, GenerationContext())
        check(seen.size == 2 && seen.map { it.parent }.distinct().size == 2)
        check(SyntheticDocx.unzip(output.toByteArray()).getValue("word/document.xml").decodeToString().contains(SECRET))
        clean()

        listOf("../escape.png", "/escape.png", "word/../escape.png", "word/%2e%2e/escape.png", "word\\escape.png").forEach { path ->
            reject(original + (path to byteArrayOf(1)), DiagnosticCodes.INVALID_TEMPLATE)
        }
        val bomb = original + ("word/media/bomb.png" to ByteArray(2_000_000) { 65 })
        check(SyntheticDocx.zip(bomb).size < 20000)
        reject(bomb, DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, GenerationContext(GenerationLimits(maxExpandedBytes = 100000)))
        reject(original, DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, GenerationContext(GenerationLimits(maxTemplateBytes = 10)))
        reject(original, DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, GenerationContext(GenerationLimits(maxArchiveEntries = 2)))
        reject(original, DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, GenerationContext(GenerationLimits(maxOutputBytes = 50)))
        listOf("word/document.xml", "word/header1.xml", "word/styles.xml").forEach { part ->
            reject(original + (part to "<!DOCTYPE document [<!ENTITY leak SYSTEM 'file:///$SECRET'>]><document>&leak;</document>".toByteArray()), DiagnosticCodes.INVALID_TEMPLATE)
        }
        listOf("word/vbaProject.bin", "word/activeX/control.bin", "word/embeddings/object.bin").forEach {
            reject(original + (it to SECRET.toByteArray()), DiagnosticCodes.UNSUPPORTED_INPUT)
        }
        reject(original + ("word/styles.xml" to original.getValue("word/styles.xml").decodeToString().replace("w:val=\"22\"", "w:val=\"$SECRET\"").toByteArray()), DiagnosticCodes.INVALID_TEMPLATE)
        val xml = original.getValue("word/document.xml").decodeToString()
        fun withText(text: String) = original + ("word/document.xml" to xml.replace("<w:body>", "<w:body><w:p><w:r><w:t>$text</w:t></w:r></w:p>").toByteArray())
        reject(withText("{#each items as item}{item}{/each}"), DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED,
            GenerationContext(GenerationLimits(maxRepetitions = 1)), data + ("items" to listOf("a", "b")))
        reject(withText("{#if true}".repeat(5) + SECRET + "{/if}".repeat(5)), DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED,
            GenerationContext(GenerationLimits(maxExpressionDepth = 2)))
        reject(original + ("word/document.xml" to ("<w:document xmlns:w=\"${SyntheticDocx.W}\">" + "<w:p>".repeat(257) + "</w:p>".repeat(257) + "</w:document>").toByteArray()), DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED)
        reject(original + ("word/document.xml" to ("<w:document xmlns:w=\"${SyntheticDocx.W}\" " + (0..256).joinToString(" ") { "a$it='x'" } + "><w:body/></w:document>").toByteArray()), DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED)

        cancellationAndIo(bytes, clean = ::clean)
        concurrentRequests(bytes, clean = ::clean)
        println("Security probe passed")
    }

    private fun cancellationAndIo(bytes: ByteArray, clean: () -> Unit) {
        var cancel = false
        var now = Instant.parse("2026-01-01T00:00:00Z")
        val clock = object : Clock() {
            override fun instant() = now
            override fun getZone() = ZoneId.of("UTC")
            override fun withZone(zone: ZoneId) = this
        }
        for (deadline in listOf(false, true)) {
            cancel = false; now = Instant.parse("2026-01-01T00:00:00Z")
            val context = GenerationContext(GenerationLimits(maxDuration = Duration.ofSeconds(1)), CancellationToken { cancel }, clock = clock)
            val input = object : ByteArrayInputStream(bytes) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    val count = super.read(buffer, offset, length)
                    if (deadline) now = now.plusSeconds(2) else cancel = true
                    return count
                }
            }
            val output = ByteArrayOutputStream()
            try { provider.generate(GenerationRequest(input, requirements, data), output, context); error("Cancellation/deadline ignored") }
            catch (error: GenerationException) { check(error.diagnostics.single().code == if (deadline) DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED else DiagnosticCodes.CANCELLED) }
            check(output.size() == 0); clean()
        }
        val output = ByteArrayOutputStream()
        val broken = object : ByteArrayInputStream(bytes) { override fun read(buffer: ByteArray, offset: Int, length: Int): Int { throw IOException(SECRET) } }
        try { provider.generate(GenerationRequest(broken, requirements, data), output, GenerationContext()); error("I/O failure ignored") }
        catch (_: IOException) { check(output.size() == 0) }
        clean()
        val brokenOutput = object : ByteArrayOutputStream() {
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                super.write(buffer, offset, minOf(5, length))
                throw IOException(SECRET)
            }
            override fun close() { error("Caller output was closed") }
        }
        try { provider.generate(GenerationRequest(bytes.inputStream(), requirements, data), brokenOutput, GenerationContext()); error("Output failure returned success") }
        catch (_: IOException) { check(brokenOutput.size() in 1..5) }
        clean()

        // Cancellation after caller resource I/O has started must close that stream and discard the staged document.
        val family = "Libre Barcode 39"
        val parts = SyntheticDocx.parts().mapValues { (_, value) -> if (value.firstOrNull() == '<'.code.toByte()) value.decodeToString().replace("Arial", family).toByteArray() else value }
        val fonts = GenerationRequirements(requirements.input, FormatRequest(DocumentFormat.DOCX,
            mapOf("fontMode" to "embed", "fonts" to listOf(mapOf("family" to family, "resource" to "font")))))
        var closed = false
        cancel = false
        val font = javaClass.getResourceAsStream("/fonts/LibreBarcode39-Regular.ttf")!!.use { it.readBytes() }
        val source = ResourceSource { object : ByteArrayInputStream(font) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(buffer, offset, length)
                cancel = true
                return count
            }
            override fun close() { closed = true; super.close() }
        } }
        val fontOutput = ByteArrayOutputStream()
        try {
            provider.generate(GenerationRequest(SyntheticDocx.zip(parts).inputStream(), fonts, data, mapOf("font" to source)), fontOutput,
                GenerationContext(cancellation = CancellationToken { cancel }))
            error("Font cancellation returned success")
        } catch (error: GenerationException) { check(error.diagnostics.single().code == DiagnosticCodes.CANCELLED) }
        check(closed && fontOutput.size() == 0)
        clean()
    }

    private fun concurrentRequests(bytes: ByteArray, clean: () -> Unit) {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val jobs = (1..12).map { index -> executor.submit(Callable {
                val output = ByteArrayOutputStream()
                val requestData = mapOf("customer" to mapOf("name" to "Tenant-$index-END"), "reference" to "Reference-$index-END")
                val context = GenerationContext(cancellation = CancellationToken { index % 3 == 0 })
                try {
                    provider.generate(GenerationRequest(bytes.inputStream(), requirements, requestData), output, context)
                    val xml = SyntheticDocx.unzip(output.toByteArray()).getValue("word/document.xml").decodeToString()
                    check(xml.contains("Tenant-$index-END") && xml.contains("Reference-$index-END"))
                    check((1..12).filter { it != index }.none { xml.contains("Tenant-$it-END") || xml.contains("Reference-$it-END") })
                } catch (error: GenerationException) {
                    check(index % 3 == 0 && error.diagnostics.single().code == DiagnosticCodes.CANCELLED && output.size() == 0)
                }
            }) }
            jobs.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally { executor.shutdownNow() }
        clean()
    }
}
