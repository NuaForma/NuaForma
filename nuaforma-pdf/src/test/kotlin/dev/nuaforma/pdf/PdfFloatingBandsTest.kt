package dev.nuaforma.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PdfFloatingBandsTest {
    private val style = PdfTextStyle("regular", 10f)
    private val page = PdfPage(240f, 220f, PdfMargins(40f, 20f, 40f, 20f))

    private fun p(text: String) = PdfParagraph(listOf(PdfTextRun(text, style)), 0f, 0f, PdfParagraphFormat(exactLineHeight = 14f))

    private fun image(top: Float = 12f, wrap: PdfImageWrap = PdfImageWrap.NONE) = PdfAnchoredImage(
        PdfImage(FoundationLetter.resource("/fixtures/nuaforma-icon-transparent.png"), 20f, 12f), 180f, top, PdfBoxAnchor.PAGE, wrap)

    private fun document(id: String): PdfDocument {
        val header = PdfPageBand(8f, 80f, listOf(p("HEADER-$id").copy(anchoredImages = listOf(image())))).withContentReservation()
        val footerText = PdfParagraph(listOf(PdfTextRun("FOOTER-$id ", style), PdfPageNumber(style)), 0f, 0f, PdfParagraphFormat(exactLineHeight = 14f))
        val boxText = PdfParagraph(listOf(PdfTextRun("BOX-$id ", style), PdfPageNumber(style)), 0f, 0f, PdfParagraphFormat(exactLineHeight = 14f))
        val frame = PdfTextBox(110f, 185f, 65f, 20f, listOf(boxText), PdfBoxAnchor.PAGE, padding = PdfMargins(0f, 0f, 0f, 0f))
        val footer = PdfPageBand(8f, 80f, listOf(frame, footerText.copy(anchoredImages = listOf(image(190f))))).withContentReservation()
        return PdfDocument(page, List(24) { p("Body-$id-${it + 1}") }, PdfFlow(header, footer))
    }

    @Test
    fun `floating images and frames repeat with resolved page fields and stay request local`() {
        val pool = Executors.newFixedThreadPool(2)
        val engine = PdfEngine()
        try {
            val jobs = listOf("A", "B").map { id -> pool.submit {
                Loader.loadPDF(engine.render(document(id), FoundationLetter.fonts())).use { pdf ->
                    assertTrue(pdf.numberOfPages > 1)
                    for (index in 0 until pdf.numberOfPages) {
                        val text = PDFTextStripper().apply { startPage = index + 1; endPage = index + 1 }.getText(pdf)
                        assertTrue(text.contains("HEADER-$id"))
                        assertTrue(text.contains("FOOTER-$id ${index + 1}"))
                        assertTrue(text.contains("BOX-$id ${index + 1}"))
                        assertFalse(text.contains("HEADER-${if (id == "A") "B" else "A"}"))
                    }
                }
            } }
            jobs.forEach { it.get(60, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)) }
    }

    @Test
    fun `unqualified wrapping overlap and physical overflow fail without an output`() {
        for (anchors in listOf(listOf(image(230f)), listOf(image(wrap = PdfImageWrap.BOTH)), listOf(image(), image()))) {
            val header = PdfPageBand(8f, 80f, listOf(p("Header").copy(anchoredImages = anchors))).withContentReservation()
            assertThrows(PdfRenderException::class.java) { PdfEngine().render(PdfDocument(page, listOf(p("Body")), PdfFlow(header)), FoundationLetter.fonts()) }
        }
    }

    @Test
    fun `unwrapped header art stays below body text without reserving extra body space`() {
        val pixel = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB)
        pixel.setRGB(0, 0, java.awt.Color(16, 112, 176).rgb)
        val bytes = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(pixel, "png", it) }.toByteArray()
        val anchor = PdfAnchoredImage(PdfImage(bytes, 80f, 20f), 20f, 40f, PdfBoxAnchor.PAGE, PdfImageWrap.NONE)
        val header = PdfPageBand(0f, 100f, listOf(p("Header").copy(anchoredImages = listOf(anchor)))).withContentReservation()
        Loader.loadPDF(PdfEngine().render(PdfDocument(page, listOf(p("MMMM")), PdfFlow(header)), FoundationLetter.fonts())).use { pdf ->
            val image = org.apache.pdfbox.rendering.PDFRenderer(pdf).renderImageWithDPI(0, 72f)
            val ink = (40 until 60).sumOf { y -> (20 until 80).count { x ->
                val color = java.awt.Color(image.getRGB(x, y))
                color.red < 80 && color.green < 80 && color.blue < 80
            } }
            assertTrue(ink > 20, "Body glyphs must remain visible over unwrapped page-band art")
        }
    }

    @Test
    fun `signed paragraph relative footer offsets preserve the ordinary text baseline`() {
        val anchored = image().copy(top = -12f, anchor = PdfBoxAnchor.PARAGRAPH)
        val plain = PdfPageBand(8f, 80f, listOf(p("Footer"))).withContentReservation()
        val floating = PdfPageBand(8f, 80f, listOf(p("Footer").copy(anchoredImages = listOf(anchored)))).withContentReservation()
        val baselines = mutableListOf<Float>()
        for (footer in listOf(plain, floating)) {
            Loader.loadPDF(PdfEngine().render(PdfDocument(page, listOf(p("Body")), PdfFlow(footer = footer)), FoundationLetter.fonts())).use { pdf ->
                object : PDFTextStripper() {
                    override fun writeString(text: String, positions: List<org.apache.pdfbox.text.TextPosition>) {
                        if (text == "Footer") baselines += positions.first().yDirAdj
                    }
                }.getText(pdf)
            }
        }
        assertEquals(2, baselines.size)
        assertEquals(baselines[0], baselines[1], .001f)
    }

}
