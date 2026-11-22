package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PageBandCellPictureLayoutTest {
    private val source = requireNotNull(javaClass.getResourceAsStream("/fixtures/nuaforma-icon-transparent.png")).use { it.readBytes() }
    private val picture = PdfImage(source, 40f, 50f)
    private val page = PdfPage(200f, 240f, PdfMargins(20f, 20f, 20f, 20f))
    private val metrics = TextMeasurer { _, _ -> error("Picture placement must not measure a font") }

    private fun place(x: Float, y: Float, baseX: Float, baseY: Float): PositionedImage {
        val cellPicture = PageBandObjects.CellPicture(PdfAnchoredImage.inTableCell(picture, x, y), baseX, baseY)
        return PageBandObjects.place(emptyList(), emptyList(), 20f, page, metrics, cellPictures = listOf(cellPicture))
            .single() as PositionedImage
    }

    @Test
    fun `cell backgrounds use the local origin and clip all four physical edges`() {
        val leftTop = place(-18f, -35f, 10f, 10f)
        assertEquals(0f, leftTop.x)
        assertEquals(0f, leftTop.top)
        assertEquals(32f, leftTop.image.width)
        assertEquals(45f, leftTop.image.height)
        assertEquals(PdfImageCrop(top = .1f, left = .2f), leftTop.image.crop)

        val rightBottom = place(25f, 15f, 160f, 180f)
        assertEquals(185f, rightBottom.x)
        assertEquals(215f, rightBottom.top)
        assertEquals(15f, rightBottom.image.width)
        assertEquals(25f, rightBottom.image.height)
        assertEquals(PdfImageCrop(right = .625f, bottom = .5f), rightBottom.image.crop)

        val inside = place(2f, 3f, 10f, 5f)
        assertSame(picture, inside.image)
        assertEquals(12f, inside.x)
        assertEquals(28f, inside.top)
        assertTrue(inside.pageY)
    }

    @Test
    fun `a picture with no visible physical rectangle fails explicitly`() {
        for ((x, y) in listOf(-40f to 0f, 0f to -70f, 200f to 0f, 0f to 220f)) {
            assertEquals(PdfFailureCode.CONTENT_OVERFLOW, assertThrows(PdfRenderException::class.java) {
                place(x, y, 0f, 0f)
            }.code)
        }
    }
}
