package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class PdfParagraphFormattingTest {
    private val color = PdfColor(221, 238, 255)
    private val style = PdfTextStyle("regular", 12f)

    @Test
    fun `paragraph backgrounds are optional immutable and retained by all copy methods`() {
        val plain = PdfParagraph(listOf(PdfTextRun("Visible", style)))
        val shaded = plain.withBackground(color)
        assertNull(plain.background)
        for (copy in listOf(shaded.copy(), shaded.withSpacing(PdfParagraphSpacing()), shaded.withPageOffset(null),
            shaded.withMinimumLineHeight(12f), shaded.withMinimumLineStyle(style), shaded.withUnderlineTrailingSpaces(true),
            shaded.withParagraphMarkStyle(style), shaded.withLongWordBreaking(true))) assertEquals(color, copy.background)
        assertNull(shaded.withBackground(null).background)
    }

    @Test
    fun `fractional automatic spacing scales baseline while preserving positive physical line height`() {
        for (multiple in listOf(1f / 240, .5f, 213f / 240, 220f / 240, .95f, 1f, 10f)) {
            assertEquals(multiple, PdfAutomaticLineSpacing(multiple).multiple)
        }
        val p = PdfParagraph(listOf(PdfTextRun("Text", style)), format =
            PdfParagraphFormat(automaticLineSpacing = PdfAutomaticLineSpacing(.5f)))
        val measured = PageLayouter.automaticBox(p, TextMeasurer { _, _ -> TextMetrics(0f, 10f, 2f, 10f, 2f, 2f) })!!
        assertEquals(7f, measured.first)
        assertEquals(6f, measured.second)
        val tiny = p.copy(format = PdfParagraphFormat(automaticLineSpacing = PdfAutomaticLineSpacing(1f / 240)))
        val error = assertThrows(PdfRenderException::class.java) {
            PageLayouter.automaticBox(tiny, TextMeasurer { _, _ -> TextMetrics(0f, 10f, 2f) })
        }
        assertEquals(PdfFailureCode.CONTENT_OVERFLOW, error.code)
        assertTrue(error.message!!.startsWith("Automatic line height is below minimum; required=1.0 pt; available="))
    }

    @Test
    fun `backgrounds join actual adjacent fragments on one canvas and remain idempotent`() {
        val first = PdfParagraph(listOf(PdfTextRun("First", style)), spaceAfter = 6f).withBackground(color)
        val second = PdfParagraph(listOf(PdfTextRun("Second", style)), spaceBefore = 9f).withBackground(PdfColor(255, 255, 0))
        val a = PositionedRectangle(10f, 20f, 100f, 20f, color, null, first, true, second)
        val b = PositionedRectangle(10f, 49f, 100f, 20f, second.background, null, second, true)
        val connected = ParagraphBackgrounds.connect(listOf(a, b))
        assertEquals(a.copy(height = 26f), connected[0])
        assertEquals(b.copy(top = 46f, height = 23f), connected[1])
        assertEquals(connected, ParagraphBackgrounds.connect(connected))
        assertEquals(listOf(a), ParagraphBackgrounds.connect(listOf(a)))
        assertEquals(listOf(b), ParagraphBackgrounds.connect(listOf(b)))
        assertEquals(listOf(a, b.copy(x = 12f)), ParagraphBackgrounds.connect(listOf(a, b.copy(x = 12f))))
        val pending = a.copy(pageOffset = PdfParagraphPageOffset(3f, PdfPageSide.EVEN))
        val next = b.copy(x = 13f)
        assertEquals(listOf(pending, next), ParagraphBackgrounds.connect(listOf(pending, next)))
        val page = PdfPage(200f, 100f, PdfMargins(0f, 0f, 0f, 0f))
        val resolved = ParagraphPageOffsets.resolve(PageLayout(page, listOf(pending, next)), 2)
        assertEquals(26f, (resolved.elements.first() as PositionedRectangle).height)
        assertEquals(46f, (resolved.elements.last() as PositionedRectangle).top)
    }

    @Test
    fun `background rectangles retain table translation and alternating page offsets`() {
        val p = PdfParagraph(listOf(PdfTextRun("X", style))).withBackground(color)
        val rect = PositionedRectangle(5f, 10f, 20f, 12f, color, null, p, true)
        val moved = FlowLayouter.translate(rect, 10f, 20f) as PositionedRectangle
        assertEquals(15f, moved.x); assertEquals(30f, moved.top)
        val marked = ParagraphPageOffsets.mark(moved, PdfParagraphPageOffset(3f, PdfPageSide.EVEN))
        val page = PdfPage(100f, 100f, PdfMargins(0f, 0f, 0f, 0f))
        assertEquals(18f, (ParagraphPageOffsets.resolve(PageLayout(page, listOf(marked)), 2).elements.single() as PositionedRectangle).x)
        for (bad in listOf(rect.copy(x = -1f), rect.copy(top = -1f), rect.copy(x = 90f), rect.copy(top = 95f)))
            assertThrows(PdfRenderException::class.java) { ParagraphBackgrounds.validate(bad, 100f, 100f) }
    }

    @Test
    fun `one shared engine keeps concurrent paragraph fills and searchable content request private`() {
        val engine = PdfEngine()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val jobs = (0..3).map { index -> executor.submit<Boolean> {
                val selected = if (index % 2 == 0) color else PdfColor(255, 255, 0)
                val p = PdfParagraph(listOf(PdfTextRun("Request$index", style)), 0f, 0f,
                    PdfParagraphFormat(exactLineHeight = 20f)).withBackground(selected)
                val document = PdfDocument(PdfPage(200f, 100f, PdfMargins(10f, 10f, 10f, 10f)), listOf(p))
                Loader.loadPDF(engine.render(document, FoundationLetter.fonts())).use { pdf ->
                    val expected = selected.red shl 16 or (selected.green shl 8) or selected.blue
                    assertEquals(expected, PDFRenderer(pdf).renderImageWithDPI(0, 72f).getRGB(150, 20) and 0xFFFFFF)
                    assertEquals("Request$index", PDFTextStripper().getText(pdf).trim())
                }
                true
            } }
            jobs.forEach { assertTrue(it.get(30, TimeUnit.SECONDS)) }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `cell decoration clips after final page offsets and keeps its translated physical canvas`() {
        val p = PdfParagraph(listOf(PdfTextRun("X", style))).withBackground(color)
        val rect = PositionedRectangle(-1.5f, 0f, 103f, 20f, color, null, p, true,
            pageOffset = PdfParagraphPageOffset(3f, PdfPageSide.EVEN), clip = RectangleClip(0f, 0f, 100f, 20f))
        val page = PdfPage(200f, 100f, PdfMargins(0f, 0f, 0f, 0f))
        val placed = ParagraphPageOffsets.resolve(PageLayout(page, listOf(rect)), 2).elements.single() as PositionedRectangle
        val clipped = ParagraphBackgrounds.clipped(placed)!!
        assertEquals(1.5f, clipped.x); assertEquals(98.5f, clipped.width)
        val moved = FlowLayouter.translate(rect, 20f, 10f) as PositionedRectangle
        assertEquals(RectangleClip(20f, 10f, 120f, 30f), moved.clip)
        assertEquals(100f, ParagraphBackgrounds.clipped(moved)!!.width)
        assertNull(ParagraphBackgrounds.clipped(rect.copy(clip = RectangleClip(200f, 200f, 210f, 210f))))
    }

    @Test
    fun `paragraph fill paints behind inline objects in both foundation and paginated output`() {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        for (x in 0..1) for (y in 0..1) image.setRGB(x, y, 0xFF0000)
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        val p = PdfParagraph(listOf(PdfImageRun(PdfImage(bytes, 20f, 20f))), 0f, 0f,
            PdfParagraphFormat(exactLineHeight = 30f)).withBackground(color)
        val page = PdfPage(200f, 100f, PdfMargins(10f, 10f, 10f, 10f))
        for (flow in listOf(null, PdfFlow())) Loader.loadPDF(PdfEngine().render(PdfDocument(page, listOf(p), flow), FoundationLetter.fonts())).use { pdf ->
            val actual = PDFRenderer(pdf).renderImageWithDPI(0, 72f)
            assertEquals(0xFF0000, actual.getRGB(20, 20) and 0xFFFFFF)
            assertEquals(0xDDEEFF, actual.getRGB(100, 20) and 0xFFFFFF)
        }
        val floating = PdfParagraph(listOf(PdfTextRun("Text", style)), format = PdfParagraphFormat(exactLineHeight = 30f),
            anchoredImages = listOf(PdfAnchoredImage(PdfImage(bytes, 20f, 20f), 30f, 0f, wrap = PdfImageWrap.NONE))).withBackground(color)
        val failure = assertThrows(PdfRenderException::class.java) {
            PdfEngine().render(PdfDocument(page, listOf(floating), PdfFlow()), FoundationLetter.fonts())
        }
        assertEquals(PdfFailureCode.UNSUPPORTED_TEXT, failure.code)
        assertEquals("Paragraph backgrounds beside floating images are not qualified", failure.message)
    }

}
