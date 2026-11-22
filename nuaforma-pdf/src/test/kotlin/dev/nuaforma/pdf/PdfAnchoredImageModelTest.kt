package dev.nuaforma.pdf

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PdfAnchoredImageModelTest {
    private val bytes = requireNotNull(javaClass.getResourceAsStream("/fixtures/nuaforma-icon-transparent.png")).use { it.readBytes() }
    private val image = PdfImage(bytes, 100f, 80f)

    @Test
    fun `only unwrapped page pictures may carry an external link`() {
        val link = PdfLink.External("https://example.invalid/manual")
        val page = PdfAnchoredImage(image, 10f, -5f, PdfBoxAnchor.PAGE, PdfImageWrap.NONE)
        val linked = page.withLink(link)
        assertEquals(link, linked.link)
        assertEquals(linked, linked.copy())
        assertEquals(linked.hashCode(), linked.copy().hashCode())
        assertNotEquals(page, linked)
        assertEquals(page, linked.withLink(null))
        for (wrong in listOf(page.copy(anchor = PdfBoxAnchor.PARAGRAPH), page.copy(wrap = PdfImageWrap.TOP_BOTTOM),
            PdfAnchoredImage.inTableCell(image, 10f, -5f))) {
            assertEquals("Picture links require fixed unwrapped placement", assertThrows(IllegalArgumentException::class.java) {
                wrong.withLink(link)
            }.message)
        }
    }

    @Test
    fun `cell pictures permit signed local positions while retaining their anchor and wrap invariants`() {
        val inside = PdfAnchoredImage.inTableCell(image, -12f, -9f)
        assertTrue(inside.cellRelative)
        assertEquals(-12f, inside.left)
        assertEquals(PdfImageWrap.NONE, inside.wrap)
        assertEquals(inside, inside.copy())
        assertNotEquals(inside.copy(left = -11f), inside)
        assertNotEquals(PdfAnchoredImage.inTableCell(image, 0f, 0f),
            PdfAnchoredImage(image, 0f, 0f, wrap = PdfImageWrap.NONE))
        for (operation in listOf<() -> Unit>({ inside.copy(anchor = PdfBoxAnchor.PAGE) },
            { inside.copy(wrap = PdfImageWrap.TOP_BOTTOM) })) {
            assertEquals("Invalid cell-relative picture", assertThrows(IllegalArgumentException::class.java) { operation() }.message)
        }
        for ((left, top) in listOf(-1f to 0f, Float.NaN to 0f, 0f to Float.NaN, 14_401f to 0f, 0f to -14_401f)) {
            assertEquals("Invalid anchored image position", assertThrows(IllegalArgumentException::class.java) {
                PdfAnchoredImage(image, left, top)
            }.message)
        }
    }

    @Test
    fun `physical picture crop keeps the raster and composes fractional edges`() {
        val base = PdfImage(bytes, 100f, 80f, PdfImageCrop(.1f, .2f, .1f, .2f))
        assertSame(base, base.withPhysicalCrop(0f, 0f, 0f, 0f))
        for ((left, top, right, bottom) in listOf(listOf(10f, 0f, 0f, 0f), listOf(0f, 10f, 0f, 0f),
            listOf(0f, 0f, 10f, 0f), listOf(0f, 0f, 0f, 10f))) {
            val cropped = base.withPhysicalCrop(left, top, right, bottom)
            assertSame(base.rasterIdentity, cropped.rasterIdentity)
            assertArrayEquals(bytes, cropped.bytes())
            assertEquals(base.width - left - right, cropped.width)
            assertEquals(base.height - top - bottom, cropped.height)
            assertEquals(.2f + left * .006f, cropped.crop.left, .00001f)
            assertEquals(.1f + top * .01f, cropped.crop.top, .00001f)
            assertEquals(.2f + right * .006f, cropped.crop.right, .00001f)
            assertEquals(.1f + bottom * .01f, cropped.crop.bottom, .00001f)
        }
        for (bad in listOf(-1f, Float.POSITIVE_INFINITY, Float.NaN)) {
            assertEquals("Invalid physical image crop", assertThrows(IllegalArgumentException::class.java) {
                base.withPhysicalCrop(bad, 0f, 0f, 0f)
            }.message)
        }
    }
}
