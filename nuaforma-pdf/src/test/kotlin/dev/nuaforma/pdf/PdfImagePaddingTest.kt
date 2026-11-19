package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.PageLayouter
import dev.nuaforma.pdf.internal.TextMeasurer
import dev.nuaforma.pdf.internal.TextMetrics
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class PdfImagePaddingTest {

    private fun image(): PdfImage {
        val pixels = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        for (x in 0..1) for (y in 0..1) pixels.setRGB(x, y, 0xFF0000)
        return PdfImage(ByteArrayOutputStream().also { ImageIO.write(pixels, "png", it) }.toByteArray(), 20f, 10f)
    }

    @Test
    fun `inline padding preserves immutable image identity and contributes to natural object metrics`() {
        val original = PdfImageRun(image())
        val padded = original.withPadding(PdfMargins(5f, 7f, 3f, 4f))
        assertEquals(PdfMargins(0f, 0f, 0f, 0f), original.padding)
        assertEquals(padded.padding, padded.copy().padding)
        assertEquals(padded, padded.copy())
        assertTrue(padded.withLineClipping(true).copy().clipToLine)
        assertTrue(padded.withLineClipping(true).withPadding(original.padding).clipToLine)
        assertFalse(original.clipToLine)
        assertNotEquals(original, padded)
        val p = PdfParagraph(listOf(padded), format = PdfParagraphFormat(automaticLineSpacing = PdfAutomaticLineSpacing()))
        assertEquals(18f to 18f, PageLayouter.automaticBox(p, TextMeasurer { _, _ -> TextMetrics(0f, 8f, 2f) }))
        assertThrows(IllegalArgumentException::class.java) { original.withPadding(PdfMargins(14_400f, 0f, 0f, 0f)) }
    }

    @Test
    fun `padding moves pixels and following text without resizing the image`() {
        val run = PdfImageRun(image()).withPadding(PdfMargins(5f, 7f, 3f, 4f))
        val p = PdfParagraph(listOf(run, PdfTextRun("X", PdfTextStyle("regular", 12f))), 0f, 0f,
            PdfParagraphFormat(exactLineHeight = 40f))
        val page = PdfPage(200f, 200f, PdfMargins(20f, 20f, 20f, 20f))
        for (flow in listOf(null, PdfFlow())) for (paragraph in listOf(p, p.copy(semantics = PdfBlockSemantics("padded-image", PdfSemanticRole.PARAGRAPH))))
            Loader.loadPDF(PdfEngine().render(PdfDocument(page, listOf(paragraph), flow), FoundationLetter.fonts())).use { pdf ->
            val raster = PDFRenderer(pdf).renderImageWithDPI(0, 72f)
            assertEquals(0xFF0000, raster.getRGB(25, 40) and 0xFFFFFF)
            assertEquals(0xFFFFFF, raster.getRGB(21, 40) and 0xFFFFFF)
            val positions = mutableListOf<TextPosition>()
            object : PDFTextStripper() {

                override fun processTextPosition(text: TextPosition) {
                    positions += text
                    super.processTextPosition(text)
                }

            }.getText(pdf)
            assertEquals(51f, positions.single { it.unicode == "X" }.xDirAdj, .01f)
        }
    }

    @Test
    fun `exact leading may overlap virtual space but actual image pixels cannot leave the page`() {
        val run = PdfImageRun(image()).withPadding(PdfMargins(0f, 0f, 100f, 0f))
        val p = PdfParagraph(listOf(run), 0f, 0f, PdfParagraphFormat(exactLineHeight = 40f))
        val page = PdfPage(200f, 200f, PdfMargins(20f, 20f, 20f, 20f))
        val error = assertThrows(PdfRenderException::class.java) { PdfEngine().render(PdfDocument(page, listOf(p), PdfFlow()), FoundationLetter.fonts()) }
        assertEquals(PdfFailureCode.CONTENT_OVERFLOW, error.code)
        assertEquals("Inline image exceeds physical top boundary; required=58.0 pt; available=0.0 pt", error.message)
    }

    @Test
    fun `explicit line clipping removes only protruding image pixels and follows physical translations`() {
        val run = PdfImageRun(image()).withPadding(PdfMargins(0f, 0f, 10f, 0f)).withLineClipping(true)
        val p = PdfParagraph(listOf(run), 0f, 0f, PdfParagraphFormat(exactLineHeight = 20f),
            semantics = PdfBlockSemantics("clipped-image", PdfSemanticRole.PARAGRAPH))
        val page = PdfPage(200f, 200f, PdfMargins(20f, 20f, 20f, 20f))
        Loader.loadPDF(PdfEngine().render(PdfDocument(page, listOf(p), PdfFlow()), FoundationLetter.fonts())).use { pdf ->
            val raster = PDFRenderer(pdf).renderImageWithDPI(0, 72f)
            assertEquals(0xFFFFFF, raster.getRGB(25, 18) and 0xFFFFFF)
            assertEquals(0xFF0000, raster.getRGB(25, 22) and 0xFFFFFF)
            assertEquals(0xFFFFFF, raster.getRGB(25, 28) and 0xFFFFFF)
        }
        val clipped = run.withPadding(PdfMargins(0f, 0f, 100f, 0f))
        Loader.loadPDF(PdfEngine().render(PdfDocument(page, listOf(p.copy(runs = listOf(clipped))), PdfFlow()), FoundationLetter.fonts())).use { pdf ->
            assertEquals(0xFFFFFF, PDFRenderer(pdf).renderImageWithDPI(0, 72f).getRGB(25, 22) and 0xFFFFFF)
        }
        val invalid = clipped.copy(image = PdfImage(ByteArray(24), 20f, 10f))
        val error = assertThrows(PdfRenderException::class.java) {
            PdfEngine().render(PdfDocument(page, listOf(p.copy(runs = listOf(invalid))), PdfFlow()), FoundationLetter.fonts())
        }
        assertEquals(PdfFailureCode.INVALID_RESOURCE, error.code)
    }

}
