package dev.nuaforma.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class PdfDrawingOrderTest {
    private val foreground = PdfDrawingLayer.FOREGROUND
    private val page = PdfPage(200f, 200f, PdfMargins(20f, 20f, 20f, 20f))
    private val text = PdfParagraph(listOf(PdfTextRun(" ", PdfTextStyle("regular", 12f))), 0f, 0f,
        PdfParagraphFormat(exactLineHeight = 20f))

    private fun box(color: PdfColor, order: PdfDrawingOrder) = PdfTextBox(40f, 80f, 80f, 40f, listOf(text),
        PdfBoxAnchor.PAGE, padding = PdfMargins(0f, 0f, 0f, 0f), fill = color).withDrawingOrder(order)

    private fun picture(): PdfImage {
        val pixels = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        for (x in 0..1) for (y in 0..1) pixels.setRGB(x, y, 0x00FF00)
        val bytes = ByteArrayOutputStream().also { ImageIO.write(pixels, "png", it) }.toByteArray()
        return PdfImage(bytes, 80f, 40f)
    }

    private fun center(blocks: List<PdfBlock>): Int = Loader.loadPDF(
        PdfEngine().render(PdfDocument(page, blocks, PdfFlow()), FoundationLetter.fonts())).use {
            PDFRenderer(it).renderImageWithDPI(0, 72f).getRGB(55, 95) and 0xFFFFFF
        }

    @Test
    fun `drawing layers sort physical objects after immutable normalization`() {
        val red = box(PdfColor(255, 0, 0), PdfDrawingOrder(foreground, 5, 1))
        val blue = box(PdfColor(0, 0, 255), PdfDrawingOrder(foreground, 10, 2))
        assertEquals(0x0000FF, center(listOf(red, blue, text)))
        assertEquals(0x0000FF, center(listOf(blue, red, text)))
        val tied = red.withDrawingOrder(PdfDrawingOrder(foreground, 10, 3))
        assertEquals(0xFF0000, center(listOf(tied, blue, text)))
        val behind = blue.withDrawingOrder(PdfDrawingOrder(PdfDrawingLayer.BACKGROUND, 4_294_967_295L, 2))
        assertEquals(0xFF0000, center(listOf(red, behind, text)))
    }

    @Test
    fun `raster and frame order share one painter without changing layout or image identity`() {
        val source = PdfAnchoredImage(picture(), 40f, 80f, PdfBoxAnchor.PAGE, PdfImageWrap.NONE)
        val ordered = source.withDrawingOrder(PdfDrawingOrder(foreground, 7, 3))
        assertNull(source.drawingOrder)
        assertEquals(ordered, ordered.copy())
        assertEquals(ordered.hashCode(), ordered.copy().hashCode())
        assertEquals(ordered.drawingOrder, ordered.copy(top = 90f).drawingOrder)
        assertSame(source.image, ordered.image)
        val red = box(PdfColor(255, 0, 0), PdfDrawingOrder(foreground, 5, 1))
        val blue = box(PdfColor(0, 0, 255), PdfDrawingOrder(foreground, 10, 2))
        val paragraph = text.copy(anchoredImages = listOf(ordered))
        assertEquals(0x0000FF, center(listOf(blue, red, paragraph)))
        assertEquals(0x00FF00, center(listOf(blue, red, text.copy(anchoredImages = listOf(
            ordered.withDrawingOrder(PdfDrawingOrder(foreground, 20, 3)))))))
        val zero = source.copy(top = 0f)
        assertNotEquals(zero, zero.copy(top = -0f))
        assertThrows(IllegalArgumentException::class.java) { PdfDrawingOrder(foreground, -1) }
        assertThrows(IllegalArgumentException::class.java) { PdfDrawingOrder(foreground, tieBreak = 4_294_967_296L) }
    }

}
