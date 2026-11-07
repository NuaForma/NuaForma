package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.imageio.IIOImage
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream

class NativeJpegImagesTest {

    private fun jpeg(progressive: Boolean = false, gray: Boolean = false): ByteArray {
        val image = BufferedImage(80, 40, if (gray) BufferedImage.TYPE_BYTE_GRAY else BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 40) for (x in 0 until 80) image.setRGB(x, y, if (x < 40) 0x008b90 else 0x253954)
        val output = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        try {
            MemoryCacheImageOutputStream(output).use { stream ->
                writer.output = stream
                val options = writer.defaultWriteParam
                options.progressiveMode = if (progressive) ImageWriteParam.MODE_DEFAULT else ImageWriteParam.MODE_DISABLED
                writer.write(null, IIOImage(image, null, null), options)
            }
        } finally { image.flush(); writer.dispose() }
        return output.toByteArray()
    }

    @Test
    fun `baseline progressive and grayscale JPEG retain decoded pixels without alpha or metadata`() {
        for (progressive in listOf(false, true)) for (gray in listOf(false, true)) {
            val bytes = jpeg(progressive, gray)
            val normalized = NativeJpegImages(GenerationContext()).decode(bytes, "word/media/logo.jpeg")
            val expected = ImageIO.read(bytes.inputStream())
            val actual = ImageIO.read(normalized.inputStream())
            assertEquals(80, actual.width)
            assertEquals(40, actual.height)
            assertFalse(actual.colorModel.hasAlpha())
            assertArrayEquals(expected.getRGB(0, 0, 80, 40, null, 0, 80), actual.getRGB(0, 0, 80, 40, null, 0, 80))
            assertArrayEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), normalized.copyOf(8))
        }
    }

    @Test
    fun `malformed JPEG disguised PNG trailing data and unsupported component counts reject with image part`() {
        val bytes = jpeg()
        val frame = (0 until bytes.size - 1).first { bytes[it] == (-1).toByte() && bytes[it + 1] == 0xc0.toByte() }
        val huffman = (0 until bytes.size - 1).first { bytes[it] == (-1).toByte() && bytes[it + 1] == 0xc4.toByte() }
        for (bad in listOf(byteArrayOf(1, 2, 3, 4), bytes.copyOf(bytes.size - 2), bytes + 0,
            bytes.copyOf().also { it[huffman + 4] = 0x7f },
            bytes.copyOf().also { it[frame + 9] = 4 }, bytes.copyOf().also { it[frame + 4] = 12 })) {
            val error = assertThrows(NativeDocxReadException::class.java) {
                NativeJpegImages(GenerationContext()).decode(bad, "word/media/bad.jpg")
            }
            assertEquals("word/media/bad.jpg", error.part)
            assertTrue(error.sourceContext.explanation.contains("JPEG"))
        }
    }

    @Test
    fun `dimensions and cumulative decoding are bounded before allocation while repeated image is cached`() {
        val bytes = jpeg()
        val frame = (0 until bytes.size - 1).first { bytes[it] == (-1).toByte() && bytes[it + 1] == 0xc0.toByte() }
        val large = bytes.copyOf().also { it[frame + 7] = 0x7f; it[frame + 8] = 0xff.toByte() }
        val oversized = assertThrows(GenerationException::class.java) { NativeJpegImages(GenerationContext()).decode(large, "word/media/huge.jpeg") }
        assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, oversized.diagnostics.single().code)
        val outputLimited = assertThrows(GenerationException::class.java) {
            NativeJpegImages(GenerationContext(GenerationLimits(maxOutputBytes = 1))).decode(bytes, "word/media/output-limit.jpg")
        }
        assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, outputLimited.diagnostics.single().code)
        val decoder = NativeJpegImages(GenerationContext(GenerationLimits(maxExpandedBytes = 64_000)))
        val first = decoder.decode(bytes, "word/media/one.jpg")
        assertSame(first, decoder.decode(bytes, "word/media/one.jpg"))
        val exceeded = assertThrows(GenerationException::class.java) { decoder.decode(bytes, "word/media/two.jpg") }
        assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, exceeded.diagnostics.single().code)
        val repeated = NativeJpegImages(GenerationContext(GenerationLimits(maxExpandedBytes = 64_000)))
        val encodedSize = repeated.decode(bytes, "word/media/repeated.jpg").size
        val repeatedLimit = assertThrows(GenerationException::class.java) {
            repeat(64_000 / encodedSize) { repeated.decode(bytes, "word/media/repeated.jpg") }
        }
        assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, repeatedLimit.diagnostics.single().code)
    }

    @Test
    fun `cancellation and independent concurrent requests do not share decoded state`() {
        val bytes = jpeg(true)
        val cancelled = GenerationContext(cancellation = CancellationToken { true })
        val error = assertThrows(GenerationException::class.java) { NativeJpegImages(cancelled).decode(bytes, "word/media/logo.jpg") }
        assertEquals(DiagnosticCodes.CANCELLED, error.diagnostics.single().code)
        val cancellation = java.util.concurrent.atomic.AtomicBoolean(false)
        val cached = NativeJpegImages(GenerationContext(cancellation = CancellationToken { cancellation.get() }))
        cached.decode(bytes, "word/media/cached.jpg")
        cancellation.set(true)
        val cachedError = assertThrows(GenerationException::class.java) { cached.decode(bytes, "word/media/cached.jpg") }
        assertEquals(DiagnosticCodes.CANCELLED, cachedError.diagnostics.single().code)
        val tasks = (1..4).map { java.util.concurrent.CompletableFuture.supplyAsync {
            NativeJpegImages(GenerationContext()).decode(bytes, "word/media/logo.jpg")
        } }
        val first = tasks.first().get()
        tasks.drop(1).forEach { assertArrayEquals(first, it.get()); assertNotSame(first, it.get()) }
    }
}
