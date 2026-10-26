package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class PdfAnchoredImageTest {
    private val page = PdfPage(200f, 200f, PdfMargins(20f, 20f, 20f, 20f))
    private val style = PdfTextStyle("regular", 10f)
    private val measurer = TextMeasurer { text, _ -> TextMetrics(text.length * 5f, 8f, 2f) }
    private fun image(crop: PdfImageCrop = PdfImageCrop()): PdfImage {
        val pixels = BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 40) for (x in 0 until 40) pixels.setRGB(x, y, if (x < 20) Color.RED.rgb else Color.BLUE.rgb)
        return PdfImage(ByteArrayOutputStream().also { ImageIO.write(pixels, "png", it) }.toByteArray(), 50f, 40f, crop)
    }
    private fun paragraph(text: String, anchors: List<PdfAnchoredImage> = emptyList(), format: PdfParagraphFormat = PdfParagraphFormat(exactLineHeight = 14f)) =
        PdfParagraph(listOf(PdfTextRun(text, style)), 0f, 0f, format, anchoredImages = anchors)
    private fun layout(blocks: List<PdfBlock>, count: Int = 8) = FlowLayouter.layout(PdfDocument(page, blocks, PdfFlow(maxPages = count)), measurer)

    @Test fun `square wrapping narrows intersecting lines and restores the complete text region below the image`() {
        for ((wrap, left) in listOf(PdfImageWrap.RIGHT to 20f, PdfImageWrap.LEFT to 130f, PdfImageWrap.LARGEST to 20f)) {
            val pages = layout(listOf(paragraph("one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen", listOf(
                PdfAnchoredImage(image(), left, 0f, wrap = wrap, distances = PdfMargins(0f, 5f, 0f, 5f))))))
            val texts = pages.single().elements.filterIsInstance<PositionedText>()
            assertTrue(texts.any { it.baseline < 60f })
            assertTrue(texts.any { it.baseline >= 70f && it.x == 20f })
            texts.filter { it.baseline - 8f < 60f }.forEach {
                if (left == 20f) assertTrue(it.x >= 75f) else assertTrue(it.x + it.text.length * 5 <= 125.001f)
            }
            assertEquals("one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen", texts.groupBy { it.baseline }.values.joinToString(" ") { line -> line.joinToString("") { it.text } }.trim())
        }
    }

    @Test fun `paragraph anchored images follow page breaks and move with their paragraph at a page boundary`() {
        val anchor = PdfAnchoredImage(image(), 20f, 0f)
        val pages = layout(List(9) { paragraph("preceding") } + paragraph("anchor remains with this paragraph", listOf(anchor)))
        assertEquals(2, pages.size)
        assertFalse(pages.first().elements.any { it is PositionedImage })
        assertEquals(20f, pages.last().elements.filterIsInstance<PositionedImage>().single().top)
        assertTrue(pages.last().elements.filterIsInstance<PositionedText>().all { it.baseline >= 68f })
        val explicit = layout(listOf(paragraph("before"), paragraph("after", listOf(anchor), PdfParagraphFormat(exactLineHeight = 14f, pageBreakBefore = true))))
        assertEquals(2, explicit.size)
        assertTrue(explicit.first().elements.none { it is PositionedImage })
    }

    @Test fun `physical page anchors retain position while their paragraph paginates`() {
        val pages = layout(List(4) { paragraph("before") } + paragraph("anchored", listOf(PdfAnchoredImage(image(), 100f, 25f, PdfBoxAnchor.PAGE, PdfImageWrap.NONE))))
        val placed = pages.single().elements.filterIsInstance<PositionedImage>().single()
        assertEquals(100f, placed.x); assertEquals(25f, placed.top)
    }

    @Test fun `two sided wrapping uses both regions on the same physical line and retains logical word order`() {
        val text = "one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen"
        val pages = layout(listOf(paragraph(text, listOf(PdfAnchoredImage(image(), 75f, 0f, wrap = PdfImageWrap.BOTH)))))
        val texts = pages.single().elements.filterIsInstance<PositionedText>()
        val first = texts.filter { it.baseline == texts.first().baseline }
        assertTrue(first.any { it.x == 20f })
        assertTrue(first.any { it.x == 125f })
        assertEquals(text.split(' '), texts.flatMap { it.text.split(' ').filter(String::isNotEmpty) })
    }

    @Test fun `shared render instances keep exclusions and crop resources isolated across concurrent requests`() {
        val engine = PdfEngine()
        val fonts = FoundationLetter.fonts()
        val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val jobs = (1..8).map { index -> executor.submit<String> {
                val doc = PdfDocument(page, listOf(paragraph("private document $index", listOf(PdfAnchoredImage(image(), 20f, 0f)))), PdfFlow())
                Loader.loadPDF(engine.render(doc, fonts)).use { PDFTextStripper().getText(it) }
            } }
            jobs.forEachIndexed { index, job -> assertEquals("private document ${index + 1}", job.get(30, java.util.concurrent.TimeUnit.SECONDS).trim()) }
        } finally { executor.shutdownNow() }
    }

    @Test fun `multiple disjoint images constrain the common region while overlapping image layers reject`() {
        val left = PdfAnchoredImage(image(), 20f, 0f, wrap = PdfImageWrap.RIGHT)
        val right = PdfAnchoredImage(image(), 130f, 0f, wrap = PdfImageWrap.LEFT)
        val layouts = layout(listOf(paragraph("one two three four five six seven eight nine ten eleven twelve", listOf(left, right))))
        val text = layouts.single().elements.filterIsInstance<PositionedText>()
        assertEquals(2, layouts.single().elements.filterIsInstance<PositionedImage>().size)
        assertTrue(text.filter { it.baseline < 60f }.all { it.x >= 70f && it.x + it.text.length * 5 <= 130.001f })
        assertEquals(PdfFailureCode.UNSUPPORTED_TEXT, assertThrows(PdfRenderException::class.java) {
            layout(listOf(paragraph("overlap", listOf(left, left))))
        }.code)
    }

    @Test fun `crop clips the decoded image to its declared physical rectangle and preserves real PDF text`() {
        val doc = PdfDocument(page, listOf(paragraph("crop proof", listOf(PdfAnchoredImage(image(PdfImageCrop(left = .5f)), 20f, 0f, wrap = PdfImageWrap.NONE)))), PdfFlow())
        Loader.loadPDF(PdfEngine().render(doc, FoundationLetter.fonts())).use { pdf ->
            assertTrue(PDFTextStripper().getText(pdf).contains("crop proof"))
            val pixels = PDFRenderer(pdf).renderImageWithDPI(0, 72f)
            assertEquals(Color.BLUE.rgb, pixels.getRGB(25, 25))
            assertEquals(Color.BLUE.rgb, pixels.getRGB(65, 55))
            for (y in 22 until 58) for (x in 22 until 68)
                assertEquals(Color.BLUE.rgb, pixels.getRGB(x, y), "Opaque foreground crop must cover underlying text at $x,$y")
            assertEquals(Color.WHITE.rgb, pixels.getRGB(15, 25))
            assertEquals(Color.WHITE.rgb, pixels.getRGB(75, 25))
        }
    }

    @Test fun `invalid geometry budgets and unsupported combinations cannot silently discard anchored content`() {
        for (crop in listOf({ PdfImageCrop(left = Float.NaN) }, { PdfImageCrop(left = .8f, right = .2f) }, { PdfImageCrop(top = -1f) }))
            assertThrows(IllegalArgumentException::class.java) { crop() }
        assertThrows(IllegalArgumentException::class.java) { PdfAnchoredImage(image(), -1f, 0f) }
        assertThrows(IllegalArgumentException::class.java) { paragraph("too many", List(9) { PdfAnchoredImage(image(), 20f, 0f) }) }
        for (anchor in listOf(PdfAnchoredImage(image(), 180f, 0f), PdfAnchoredImage(image(), 20f, 170f),
            PdfAnchoredImage(image(), 20f, 0f, distances = PdfMargins(10f, 0f, 0f, 0f))))
            assertThrows(PdfRenderException::class.java) { layout(listOf(paragraph("overflow", listOf(anchor)))) }
        val p = paragraph("unsupported keep", listOf(PdfAnchoredImage(image(), 20f, 0f)), PdfParagraphFormat(exactLineHeight = 14f, keepWithNext = true))
        assertEquals(PdfFailureCode.UNSUPPORTED_TEXT, assertThrows(PdfRenderException::class.java) { layout(listOf(p)) }.code)
        val rtl = paragraph("אבגד 123", listOf(PdfAnchoredImage(image(), 20f, 0f)), PdfParagraphFormat(exactLineHeight = 14f, textDirection = PdfTextDirection.RIGHT_TO_LEFT))
        assertEquals(PdfFailureCode.UNSUPPORTED_TEXT, assertThrows(PdfRenderException::class.java) { layout(listOf(rtl)) }.code)
        assertThrows(PdfRenderException::class.java) { PageLayouter.layout(PdfDocument(page, listOf(p)), measurer) }
        val cell = PdfTableCell(listOf(paragraph("band anchor", listOf(PdfAnchoredImage(image(), 20f, 0f)))))
        val band = PdfPageBand(0f, 20f, listOf(PdfTable(listOf(160f), listOf(PdfTableRow(listOf(cell))))))
        assertEquals(PdfFailureCode.UNSUPPORTED_TEXT, assertThrows(PdfRenderException::class.java) {
            val policy = PdfFlow(header = band)
            val layouts = FlowLayouter.layout(PdfDocument(page, listOf(paragraph("body")), policy), measurer)
            FlowLayouter.decorate(layouts, policy, 1, 1, measurer)
        }.code)
        assertThrows(PdfRenderException::class.java) { layout(List(9) { paragraph("before") } + paragraph("over budget", listOf(PdfAnchoredImage(image(), 20f, 0f))), 1) }
    }
}
