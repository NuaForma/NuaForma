package dev.nuaforma.docx

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.internal.ImageContent
import dev.nuaforma.docx.internal.ImagePipe
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.Executors
import java.util.zip.CRC32
import javax.imageio.ImageIO

/** Reproducible image and OOXML fixture builder; no binary golden or private document. */
class ImagePipeTest {
    private fun image(width: Int = 96, height: Int = 48, format: String = "png", alpha: Boolean = false): ByteArray {
        val image = BufferedImage(width, height, if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        image.createGraphics().let { graphics ->
            graphics.color = Color(0, 140, 150, if (alpha) 128 else 255); graphics.fillRect(0, 0, width, height)
            graphics.color = Color(20, 40, 75); graphics.fillRect(width / 4, height / 4, width / 2, height / 2)
            graphics.dispose()
        }
        return ByteArrayOutputStream().also { assertTrue(ImageIO.write(image, format, it)) }.toByteArray().also { image.flush() }
    }
    private fun encoded(bytes: ByteArray = image()): String = Base64.getEncoder().encodeToString(bytes)
    private fun apply(value: Any?, width: Number = 50, height: Number = 50, ratio: Boolean = true,
                      generation: GenerationContext = GenerationContext(), pipe: ImagePipe = ImagePipe(generation)): ContentResult =
        pipe.apply(value, listOf(width, height, ratio), ContentPipeContext(java.util.Locale.ROOT, java.time.ZoneId.of("UTC"), generation))

    @Test
    fun `PNG JPEG portrait landscape and sizing maxima follow explicit millimetres and no upscale policy`() {
        for (format in listOf("png", "jpeg")) {
            val source = encoded(image(format = format))
            val fit = apply(source, 20, 3) as ImageContent
            assertEquals(216000, fit.widthEmu); assertEquals(108000, fit.heightEmu)
            val noRatio = apply(source, 20, 3, false) as ImageContent
            assertEquals(720000, noRatio.widthEmu); assertEquals(108000, noRatio.heightEmu)
            val small = apply(source) as ImageContent
            assertEquals(914400, small.widthEmu); assertEquals(457200, small.heightEmu)
            val portrait = apply(encoded(image(48, 96, format)), 3, 20) as ImageContent
            assertEquals(108000, portrait.widthEmu); assertEquals(216000, portrait.heightEmu)
            val copy = small.bytes(); copy.fill(0)
            assertNotEquals(0, small.bytes()[0].toInt())
        }
        assertSame(ContentResult.Empty, apply(null))
        assertEquals("image", DocxProvider().contentPipes().single { it.name == "image" }.name)
        assertFalse(DocxProvider().contentPipes().single { it.name == "image" }.chainable)
        assertEquals(setOf("image/png", "image/jpeg"), DocxProvider().contentPipes().single { it.name == "image" }.contentTypes)
    }

    @Test
    fun `malformed unsupported animated corrupt and non-base64 values fail without exposing data`() {
        val good = image()
        val corrupt = good.copyOf().apply { this[lastIndex] = 1 }
        val animated = good.copyOfRange(0, 33) + chunk("acTL", ByteArray(8)) + good.copyOfRange(33, good.size)
        for (value in listOf("", "private-image-payload", "data:image/png;base64,${encoded(good)}", " ${encoded(good)}", encoded(corrupt), encoded(animated),
            encoded("<svg>private-image-payload</svg>".toByteArray()), encoded(good + byteArrayOf(1)), 12, emptyMap<String, Any>())) {
            val error = assertThrows(GenerationException::class.java) { apply(value) }
            assertEquals(DiagnosticCodes.INVALID_REQUEST, error.diagnostics.single().code)
            assertFalse(error.message.orEmpty().contains("private-image-payload")); assertNull(error.cause)
        }
        for (width in listOf(-1, 0, 0.09, 501, Double.NaN)) assertThrows(GenerationException::class.java) { apply(null, width) }
    }

    @Test
    fun `header pixel encoded count cumulative and cancellation budgets apply before costly decode`() {
        val bomb = image().copyOf()
        ByteBuffer.wrap(bomb, 16, 4).putInt(100000)
        ByteBuffer.wrap(bomb, 29, 4).putInt(CRC32().apply { update(bomb, 12, 17) }.value.toInt())
        assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED,
            assertThrows(GenerationException::class.java) { apply(encoded(bomb)) }.diagnostics.single().code)
        assertThrows(GenerationException::class.java) { apply("A".repeat(8 * 1024 * 1024 + 4)) }
        val generation = GenerationContext(GenerationLimits(maxExpandedBytes = 300000))
        val cumulative = ImagePipe(generation)
        val image = encoded(image(128, 128))
        apply(image, generation = generation, pipe = cumulative)
        assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED,
            assertThrows(GenerationException::class.java) { apply(image, generation = generation, pipe = cumulative) }.diagnostics.single().code)
        val count = ImagePipe(GenerationContext())
        repeat(128) { apply(encoded(image(1, 1)), pipe = count) }
        assertThrows(GenerationException::class.java) { apply(encoded(image(1, 1)), pipe = count) }
        val cancelled = GenerationContext(cancellation = CancellationToken { true })
        assertEquals(DiagnosticCodes.CANCELLED,
            assertThrows(GenerationException::class.java) { apply(encoded(), generation = cancelled) }.diagnostics.single().code)
    }

    @Test
    fun `insertion preserves split run style scope relationships headers footers and both text box kinds`() {
        val source = SyntheticDocx.parts().apply {
            keys.filter { it.endsWith(".xml") }.forEach { key ->
                this[key] = getValue(key).decodeToString().replace("name}", "name | image(10, 5)}").toByteArray()
            }
            this["word/document.xml"] = getValue("word/document.xml").decodeToString().replace("<w:body>",
                "<w:body><w:p><w:r><w:t>{#each items as item}{#if item != null}Before {item | image(10, 5)} after{/if}{/each}</w:t></w:r></w:p>").toByteArray()
            this["word/footer1.xml"] = getValue("word/footer1.xml").decodeToString().replace("{reference}", "{customer.name | image(10, 5)}").toByteArray()
        }
        val data = mapOf("customer" to mapOf("name" to encoded()), "reference" to "NF001", "items" to listOf(encoded(), null, encoded(image(format = "jpeg"))))
        val output = generate(source, data)
        val parts = SyntheticDocx.unzip(output)
        assertArrayEquals(source.getValue("word/media/mark.png"), parts.getValue("word/media/mark.png"))
        val allXml = parts.filterKeys { it.endsWith(".xml") }.values.joinToString("") { it.decodeToString() }
        assertFalse(allXml.contains("| image")); assertTrue(allXml.contains("Before ")); assertTrue(allXml.contains(" after"))
        val ids = Regex("<wp:docPr[^>]*id=\"([0-9]+)\"").findAll(allXml).map { it.groupValues[1] }.toList()
        assertEquals(ids.size, ids.distinct().size)
        for (owner in listOf("document", "header1", "footer1")) {
            val xml = parts.getValue("word/$owner.xml").decodeToString()
            val rels = parts.getValue("word/_rels/$owner.xml.rels").decodeToString()
            assertTrue(xml.contains("NuaFormaImage"), owner)
            Regex("r:embed=\"([^\"]+)\"").findAll(xml).forEach { assertTrue(rels.contains("Id=\"${it.groupValues[1]}\""), owner) }
        }
        val directory = Path.of("build/image-pipe-reference"); Files.createDirectories(directory)
        Files.write(directory.resolve("generated.docx"), output)
        Files.write(directory.resolve("template.docx"), SyntheticDocx.zip(source))
    }

    @Test
    fun `null missing unsupported locations and concurrent requests retain transactional generation`() {
        fun source(marker: String) = SyntheticDocx.parts(marker)
        val data = mapOf("customer" to mapOf("name" to "Ada"), "reference" to "NF001", "picture" to null)
        assertTrue(generate(source("{picture | image()}"), data).isNotEmpty())
        assertTrue(generate(source("{missing ?? null | image()}"), data).isNotEmpty())
        for (marker in listOf("{missing | image()}", "{picture | image(0)}", "{picture | image() | image()}")) {
            val output = ByteArrayOutputStream()
            assertThrows(GenerationException::class.java) { generate(source(marker), data, output) }
            assertEquals(0, output.size())
        }
        val fontsBefore = ImageIO.getUseCache()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..4).map { executor.submit<ByteArray> {
                generate(source("{picture | image()}"), data + ("picture" to encoded()))
            } }
            futures.forEach { assertTrue(it.get().isNotEmpty()) }
        } finally { executor.shutdownNow() }
        assertEquals(fontsBefore, ImageIO.getUseCache())
    }

    private fun generate(parts: Map<String, ByteArray>, data: Map<String, *>, output: ByteArrayOutputStream = ByteArrayOutputStream()): ByteArray {
        DocxProvider().generate(GenerationRequest(SyntheticDocx.zip(parts).inputStream(), GenerationRequirements(
            FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.DOCX)), data), output, GenerationContext())
        return output.toByteArray()
    }
    private fun chunk(type: String, bytes: ByteArray): ByteArray = ByteBuffer.allocate(bytes.size + 12).putInt(bytes.size)
        .put(type.toByteArray(Charsets.US_ASCII)).put(bytes).putInt(CRC32().apply { update(type.toByteArray(Charsets.US_ASCII)); update(bytes) }.value.toInt()).array()
}
