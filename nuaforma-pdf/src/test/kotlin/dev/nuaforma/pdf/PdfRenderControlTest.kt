package dev.nuaforma.pdf

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class PdfRenderControlTest {
    private fun document() = PdfDocument(PdfPage(200f, 200f, PdfMargins(12f, 12f, 12f, 12f)),
        listOf(PdfParagraph(listOf(PdfTextRun("Control", PdfTextStyle("regular", 11f))))))

    @Test fun `host cancellation checkpoint interrupts active rendering`() {
        var checkpoints = 0
        val stop = IllegalStateException("Synthetic host cancellation")
        val control = PdfRenderControl(checkpoint = Runnable { if (++checkpoints == 4) throw stop })
        assertSame(stop, assertThrows(IllegalStateException::class.java) {
            PdfEngine().render(document(), FoundationLetter.fonts(), PdfOutputOptions(), control)
        })
    }

    @Test fun `output is bounded while writing rather than after an oversized PDF is allocated`() {
        val failure = assertThrows(PdfRenderException::class.java) {
            PdfEngine().render(document(), FoundationLetter.fonts(), PdfOutputOptions(), PdfRenderControl(64))
        }
        assertEquals(PdfFailureCode.RESOURCE_LIMIT, failure.code)
    }

    @Test fun `decoded images are budgeted before decoding and cached image identities count once`() {
        val bytes = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        val image = PdfImage(bytes, 12f, 12f)
        val model = PdfDocument(document().page, listOf(image, image))
        assertEquals(PdfFailureCode.RESOURCE_LIMIT, assertThrows(PdfRenderException::class.java) {
            PdfEngine().render(model, FoundationLetter.fonts(), PdfOutputOptions(), PdfRenderControl(maxDecodedImageBytes = 63))
        }.code)
        assertTrue(PdfEngine().render(model, FoundationLetter.fonts(), PdfOutputOptions(), PdfRenderControl(maxDecodedImageBytes = 64)).size > 100)
    }
}
