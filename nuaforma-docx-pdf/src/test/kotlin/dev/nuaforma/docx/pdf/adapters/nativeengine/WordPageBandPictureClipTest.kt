package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.pdf.PdfImage
import dev.nuaforma.pdf.PdfImageCrop
import dev.nuaforma.pdf.PdfMargins
import dev.nuaforma.pdf.PdfPage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WordPageBandPictureClipTest {
    private val page = PdfPage(220f, 260f, PdfMargins(20f, 20f, 20f, 20f))
    private val picture = PdfImage(ByteArray(24), 100f, 40f, PdfImageCrop(.1f, .2f, .2f, .1f))

    @Test
    fun `page clipping composes with the original source crop`() {
        val left = requireNotNull(WordPageBandPictureClip.clip(picture, -10f, -8f, page))
        assertEquals(0f, left.left)
        assertEquals(0f, left.top)
        assertEquals(90f, left.image.width)
        assertEquals(32f, left.image.height)
        assertEquals(.17f, left.image.crop.left, .00001f)
        assertEquals(.24f, left.image.crop.top, .00001f)
        assertEquals(.2f, left.image.crop.right)
        assertEquals(.2f, left.image.crop.bottom)

        val right = requireNotNull(WordPageBandPictureClip.clip(picture, 140f, 228f, page))
        assertEquals(80f, right.image.width)
        assertEquals(32f, right.image.height)
        assertEquals(.34f, right.image.crop.right, .00001f)
        assertEquals(.34f, right.image.crop.bottom, .00001f)
    }

    @Test
    fun `fully outside pictures and subpoint slivers remain unsupported`() {
        assertNull(WordPageBandPictureClip.clip(picture, -100f, 20f, page))
        assertNull(WordPageBandPictureClip.clip(picture, 20f, 260f, page))
        assertNull(WordPageBandPictureClip.clip(picture, 219.5f, 20f, page))
    }
}
