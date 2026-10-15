package dev.nuaforma.docx

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.adapters.imageio.ImageDecoder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.util.Locale
import javax.imageio.ImageIO

class ImageCancellationTest {
    @ParameterizedTest
    @ValueSource(strings = ["png", "jpeg"])
    fun `decoder and normalization observe cancellation after pixel reservation`(format: String) {
        val image = BufferedImage(512, 256, BufferedImage.TYPE_INT_RGB)
        val source = ByteArrayOutputStream().also { assertTrue(ImageIO.write(image, format, it)) }.toByteArray()
        var reserved = false
        var processingChecks = 0
        val generation = GenerationContext(cancellation = CancellationToken { reserved && ++processingChecks > 10 })
        val context = ContentPipeContext(Locale.ROOT, ZoneId.of("UTC"), generation)
        val error = assertThrows(GenerationException::class.java) {
            ImageDecoder.decode(source, context) { width, height ->
                assertEquals(512, width); assertEquals(256, height)
                reserved = true
            }
        }
        assertTrue(reserved)
        assertTrue(processingChecks > 10)
        assertEquals(DiagnosticCodes.CANCELLED, error.diagnostics.single().code)
        assertNull(error.cause)
    }
}
