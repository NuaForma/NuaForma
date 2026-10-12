package dev.nuaforma

import dev.nuaforma.contracts.*
import dev.nuaforma.core.ProviderRegistry
import java.io.FilterInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit container qualification entry point; not a server or a production distribution. */
object PdfEngineQualification {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(Runtime.version().feature() == 17 && arguments.size == 3)
        val fixtures = Path.of(arguments[0])
        val fonts = Path.of(arguments[1])
        val destination = Path.of(arguments[2])
        Files.createDirectory(destination)
        System.setProperty("nuaforma.pdf.launcher", "/opt/nuaforma/bin/render-pdf")
        val registry = ProviderRegistry.discover()
        check(registry.diagnostics.isEmpty())
        check(registry.providers.map { it.metadata.id }.toSet() == setOf("nuaforma-docx", "nuaforma-docx-pdf"))
        val declarations = listOf(
            mapOf("family" to "Arial", "face" to "regular", "resource" to "arial"),
            mapOf("family" to "Arial", "face" to "bold", "resource" to "arialbd"),
            mapOf("family" to "Arial", "face" to "italic", "resource" to "ariali"),
            mapOf("family" to "Arial", "face" to "boldItalic", "resource" to "arialbi"),
            mapOf("family" to "Libre Barcode 39", "face" to "regular", "resource" to "barcode"),
        )
        val opened = AtomicInteger()
        val closed = AtomicInteger()
        val resources = declarations.associate { declaration ->
            val id = declaration.getValue("resource")
            id to ResourceSource {
                opened.incrementAndGet()
                object : FilterInputStream(Files.newInputStream(fonts.resolve(if (id == "barcode") "LibreBarcode39-Regular.ttf" else "$id.ttf"))) {
                    override fun close() { super.close(); closed.incrementAndGet() }
                }
            }
        }
        fun generate(source: String, name: String, reference: String, watermark: TextWatermark? = null,
            selectedResources: Map<String, ResourceSource> = resources, context: GenerationContext = GenerationContext()) {
            val options = mapOf("fontMode" to "validate", "fonts" to declarations,
                "paragraphDefaults" to mapOf("afterTwips" to 160, "line240ths" to 278))
            val requirements = GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.PDF, options), GenerationOptions(watermark))
            check(registry.select(requirements).metadata.id == "nuaforma-docx-pdf")
            Files.newInputStream(fixtures.resolve(source)).use { input ->
                Files.newOutputStream(destination.resolve("$name.pdf")).use { output ->
                    val result = registry.generate(GenerationRequest(input, requirements,
                        mapOf("customer" to mapOf("name" to "Ada"), "reference" to reference), selectedResources), output, context)
                    check(result.output.format == DocumentFormat.PDF && result.output.options["version"] == "1.7")
                }
            }
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val jobs = listOf(
                Callable { generate("font-reference/template.docx", "tenant-a", "NF001") },
                Callable { generate("font-reference/template.docx", "tenant-b", "NF002") },
            )
            pool.invokeAll(jobs).forEach { it.get(120, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        generate("watermark-reference/plain.docx", "watermark-dynamic", "NF-001",
            TextWatermark("{#if true}CONCEPT{#else}FINAL{/if} — {customer.name}"))
        val previewFont = Files.readAllBytes(fonts.resolve("LibreBarcode39-Regular.ttf"))
        val sfnt = java.nio.ByteBuffer.wrap(previewFont).order(java.nio.ByteOrder.BIG_ENDIAN)
        val tableCount = sfnt.getShort(4).toInt() and 65535
        val os2 = (0 until tableCount).map { 12 + it * 16 }.single { sfnt.getInt(it) == 0x4F532F32 }
        // Synthetic fixture variation only: the actual caller-font permission reader never rewrites flags.
        sfnt.putShort(sfnt.getInt(os2 + 8) + 8, 4)
        fun checksum(start: Int, length: Int): Long {
            var total = 0L
            for (offset in start until start + length step 4) {
                var value = 0L
                repeat(4) { part -> value = (value shl 8) or if (offset + part < start + length) previewFont[offset + part].toLong() and 255 else 0 }
                total = (total + value) and 0xFFFFFFFFL
            }
            return total
        }
        sfnt.putInt(os2 + 4, checksum(sfnt.getInt(os2 + 8), sfnt.getInt(os2 + 12)).toInt())
        val head = (0 until tableCount).map { 12 + it * 16 }.single { sfnt.getInt(it) == 0x68656164 }
        val adjustment = sfnt.getInt(head + 8) + 8
        sfnt.putInt(adjustment, 0)
        sfnt.putInt(adjustment, (0xB1B0AFBAL - checksum(0, previewFont.size)).toInt())
        val previewResources = resources + ("barcode" to ResourceSource {
            opened.incrementAndGet()
            object : ByteArrayInputStream(previewFont) { override fun close() { super.close(); closed.incrementAndGet() } }
        })
        generate("font-reference/template.docx", "print-preview", "NF001", selectedResources = previewResources)
        generate("font-reference/isolated-embedded.docx", "template-embedded", "NF001")
        val cancelledDuringConversion = AtomicBoolean()
        val context = GenerationContext(cancellation = CancellationToken {
            if (!cancelledDuringConversion.get()) ProcessHandle.current().descendants().use { processes ->
                if (processes.anyMatch { process ->
                    val info = process.info()
                    info.command().orElse("").endsWith("/soffice.bin") && info.arguments().orElse(emptyArray()).contains("--convert-to")
                }) cancelledDuringConversion.set(true)
            }
            cancelledDuringConversion.get()
        })
        val rejected = ByteArrayOutputStream()
        val requirements = GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.PDF,
            mapOf("fontMode" to "validate", "fonts" to declarations, "paragraphDefaults" to mapOf("afterTwips" to 160, "line240ths" to 278))))
        Files.newInputStream(fixtures.resolve("font-reference/template.docx")).use { input ->
            try {
                registry.generate(GenerationRequest(input, requirements,
                    mapOf("customer" to mapOf("name" to "Ada"), "reference" to "NF001"), resources), rejected, context)
                error("Real conversion ignored cancellation")
            } catch (failure: GenerationException) {
                check(cancelledDuringConversion.get() && failure.diagnostics.any { it.code == DiagnosticCodes.CANCELLED })
                check(rejected.size() == 0)
            }
        }
        check(opened.get() == 30 && closed.get() == opened.get())
        check(ProcessHandle.current().descendants().use { it.noneMatch { process -> process.isAlive } })
        check(Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { paths -> paths.noneMatch { it.fileName.toString().startsWith("nuaforma-pdf-") } })
        println("Qualified core discovery, PDF selection, concurrent tenant data, dynamic watermark, print/preview embedding, actual conversion cancellation, resource closing and cleanup")
    }
}
