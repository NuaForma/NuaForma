package dev.nuaforma.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PdfTextBoxTest {
    private val page = PdfPage(400f, 500f, PdfMargins(30f, 30f, 30f, 30f))
    private fun p(value: String) = PdfParagraph(listOf(PdfTextRun(value, PdfTextStyle("regular", 10f))), 0f, 0f, PdfParagraphFormat(exactLineHeight = 16f))

    @Test fun `same engine and immutable resources support concurrent rotated frame renders without cross request content`() {
        val engine = PdfEngine()
        val fonts = FoundationLetter.fonts()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val jobs = (1..12).map { number -> executor.submit<String> {
                val rgb = (number shl 16) or (120 shl 8) or 140
                val pixels = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, rgb) }
                val png = ByteArrayOutputStream().also { ImageIO.write(pixels, "png", it) }.toByteArray()
                val imageParagraph = PdfParagraph(listOf(PdfImageRun(PdfImage(png, 8f, 8f))), 0f, 0f, PdfParagraphFormat(exactLineHeight = 16f))
                val model = PdfDocument(page, listOf(p("BODY $number"), PdfTextBox(100f, 150f, 180f, 50f,
                    listOf(p("PRIVATE%03d".format(java.util.Locale.ROOT, number)), imageParagraph), PdfBoxAnchor.PAGE, if (number % 2 == 0) 90f else 270f,
                    fill = PdfColor(number, 120, 140), border = PdfBorder(.5f, PdfColor(0, 0, 0)))), PdfFlow())
                Loader.loadPDF(engine.render(model, fonts)).use { pdf ->
                    assertEquals(1, pdf.numberOfPages)
                    assertTrue(pdf.getPage(0).resources.fontNames.all { pdf.getPage(0).resources.getFont(it).isEmbedded })
                    val picture = pdf.getPage(0).resources.getXObject(pdf.getPage(0).resources.xObjectNames.single()) as PDImageXObject
                    assertEquals(rgb, picture.image.getRGB(0, 0) and 0xffffff)
                    val logical = StringBuilder()
                    object : PDFTextStripper() {
                        override fun processTextPosition(text: TextPosition) { logical.append(text.unicode) }
                    }.getText(pdf)
                    logical.toString()
                }
            } }
            jobs.forEachIndexed { index, job ->
                val text = job.get(30, TimeUnit.SECONDS)
                assertEquals(listOf("PRIVATE%03d".format(java.util.Locale.ROOT, index + 1)), Regex("PRIVATE\\d{3}").findAll(text).map { it.value }.toList())
                assertTrue(text.contains("BODY ${index + 1}"))
            }
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)) }
    }

    @Test fun `frame overflow rotated physical bounds and unsupported wrapping fail explicitly`() {
        val fonts = FoundationLetter.fonts()
        for (box in listOf(
            PdfTextBox(50f, 50f, 100f, 30f, List(5) { p("Too much") }, PdfBoxAnchor.PAGE),
            PdfTextBox(0f, 0f, 180f, 50f, listOf(p("Outside")), PdfBoxAnchor.PAGE, 45f),
        )) assertEquals(PdfFailureCode.CONTENT_OVERFLOW, assertThrows(PdfRenderException::class.java) { PdfEngine().render(PdfDocument(page, listOf(box), PdfFlow()), fonts) }.code)
        assertThrows(IllegalArgumentException::class.java) { PdfTextBox(30f, 0f, 150f, 40f, listOf(p("Invalid")), rotation = 90f, wrap = PdfBoxWrap.TOP_BOTTOM) }
        assertThrows(IllegalArgumentException::class.java) { PdfTextBox(Float.NaN, 0f, 150f, 40f, listOf(p("Invalid"))) }
    }

    @Test fun `wrapped frame moves whole to a fresh body area and respects page budget`() {
        val box = PdfTextBox(30f, 0f, 250f, 150f, listOf(p("Wrapped")), wrap = PdfBoxWrap.TOP_BOTTOM)
        val blocks = List(20) { p("Entry $it") } + box + p("After")
        Loader.loadPDF(PdfEngine().render(PdfDocument(page, blocks, PdfFlow()), FoundationLetter.fonts())).use { pdf ->
            assertEquals(2, pdf.numberOfPages)
            val text = PDFTextStripper().apply { startPage = 2; endPage = 2 }.getText(pdf)
            assertTrue(text.contains("Wrapped") && text.contains("After"))
        }
        assertEquals(PdfFailureCode.RESOURCE_LIMIT, assertThrows(PdfRenderException::class.java) {
            PdfEngine().render(PdfDocument(page, blocks, PdfFlow(maxPages = 1)), FoundationLetter.fonts())
        }.code)
    }
}
