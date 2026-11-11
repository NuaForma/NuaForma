package dev.nuaforma.docx

import dev.nuaforma.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO

class DocxImagesTest {

    private fun source(format: String, alpha: Boolean = false): ByteArray {
        val image = BufferedImage(32, 16, if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, if (alpha) 0x77112233 else 0xff008b90.toInt())
        return ByteArrayOutputStream().also { assertTrue(ImageIO.write(image, format, it)); image.flush() }.toByteArray()
    }

    @Test
    fun `normalization retains dimensions and pixels including PNG transparency without mutating its input`() {
        for ((format, alpha) in listOf("png" to false, "png" to true, "jpeg" to false)) {
            val bytes = source(format, alpha)
            val snapshot = bytes.copyOf()
            val output = DocxImages.normalize(bytes)
            val expected = ImageIO.read(bytes.inputStream())
            val actual = ImageIO.read(output.inputStream())
            assertEquals(32, actual.width)
            assertEquals(16, actual.height)
            assertEquals(alpha, actual.colorModel.hasAlpha())
            assertArrayEquals(expected.getRGB(0, 0, 32, 16, null, 0, 32), actual.getRGB(0, 0, 32, 16, null, 0, 32))
            assertArrayEquals(snapshot, bytes)
            assertNotSame(output, DocxImages.normalize(bytes))
        }
    }

    @Test
    fun `malformed input is rejected without resolving paths or URLs`() {
        for (bad in listOf(byteArrayOf(1, 2, 3), "https://invalid.example/image.png".toByteArray(), source("jpeg").dropLast(2).toByteArray())) {
            val error = assertThrows(GenerationException::class.java) { DocxImages.normalize(bad) }
            assertEquals(DiagnosticCodes.INVALID_REQUEST, error.diagnostics.single().code)
        }
    }

    @Test
    fun `encoded decoded and output limits and cancellation are enforced`() {
        val bytes = source("png")
        for ((data, limits) in listOf(
            ByteArray(4_194_305) to GenerationLimits(), bytes to GenerationLimits(maxExpandedBytes = 1_024),
            bytes to GenerationLimits(maxOutputBytes = 1))) {
            val error = assertThrows(GenerationException::class.java) { DocxImages.normalize(data, GenerationContext(limits)) }
            assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, error.diagnostics.single().code)
        }
        val error = assertThrows(GenerationException::class.java) {
            DocxImages.normalize(bytes, GenerationContext(cancellation = CancellationToken { true }))
        }
        assertEquals(DiagnosticCodes.CANCELLED, error.diagnostics.single().code)
    }

    @Test
    fun `independent callers receive private output buffers without global codec changes`() {
        val bytes = source("jpeg")
        val calls = (1..4).map { CompletableFuture.supplyAsync { DocxImages.normalize(bytes) } }
        val expected = calls.first().get()
        calls.drop(1).forEach { assertArrayEquals(expected, it.get()); assertNotSame(expected, it.get()) }
    }
}
