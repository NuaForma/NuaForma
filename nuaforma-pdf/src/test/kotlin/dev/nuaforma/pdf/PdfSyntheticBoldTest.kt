package dev.nuaforma.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.pdfparser.PDFStreamParser
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PdfSyntheticBoldTest {

    private fun model(bold: Boolean): PdfDocument {
        val plain = PdfTextStyle("regular", 16f)
        val styled = plain.copy(color = PdfColor(28, 126, 130), underline = true)
            .withStrikethrough(true).withFontFeatures(PdfFontFeatures(PdfKerning.OPENTYPE, true)).withSyntheticBold(bold)
        return PdfDocument(PdfPage(595.28f, 841.89f, PdfMargins(36f, 36f, 36f, 36f)), listOf(
            PdfParagraph(listOf(PdfTextRun("AVATAR office fi", styled)), 0f, 0f, PdfParagraphFormat(exactLineHeight = 40f)),
            PdfParagraph(listOf(PdfTextRun("FOLLOWING NORMAL", plain)), 0f, 0f, PdfParagraphFormat(exactLineHeight = 40f))))
    }

    private fun render(engine: PdfEngine, bold: Boolean) = engine.render(model(bold),
        listOf(PdfFont("regular", FoundationLetter.resource("/fonts/NotoSerif-Light.ttf"))))

    private fun positions(bytes: ByteArray): List<Triple<String, Float, Float>> {
        val result = mutableListOf<Triple<String, Float, Float>>()
        Loader.loadPDF(bytes).use { pdf ->
            object : PDFTextStripper() {

                override fun processTextPosition(position: TextPosition) {
                    result += Triple(position.unicode, position.xDirAdj, position.yDirAdj)
                    super.processTextPosition(position)
                }

            }.getText(pdf)
        }
        return result
    }

    @Test
    fun `synthetic bold preserves shaped glyphs and advances without leaking into later text`() {
        val engine = PdfEngine()
        val regular = render(engine, false)
        val bold = render(engine, true)
        assertEquals(positions(regular), positions(bold))
        Loader.loadPDF(regular).use { plain -> Loader.loadPDF(bold).use { actual ->
            val first = PDFRenderer(plain).renderImageWithDPI(0, 96f)
            val second = PDFRenderer(actual).renderImageWithDPI(0, 96f)
            assertTrue((35..first.height - 1).any { y -> (35..first.width - 1).any { x -> first.getRGB(x, y) != second.getRGB(x, y) } })
            val tail = positions(bold).first { it.first == "F" }
            val top = ((tail.third - 20f) * 96f / 72f).toInt()
            val bottom = ((tail.third + 5f) * 96f / 72f).toInt()
            for (y in top..bottom) for (x in 0 until first.width) assertEquals(first.getRGB(x, y), second.getRGB(x, y), "Following plain text changed at $x,$y")
            assertTrue(PDFStreamParser(actual.getPage(0)).parse().filterIsInstance<Operator>().any { it.name == "Tr" })
        } }
    }

    @Test
    fun `shared engine keeps synthetic text state isolated across concurrent documents`() {
        val engine = PdfEngine()
        val workers = Executors.newFixedThreadPool(2)
        try {
            val results = (0..3).map { index -> workers.submit<Boolean> {
                val expected = index % 2 == 0
                Loader.loadPDF(render(engine, expected)).use { pdf ->
                    val operators = PDFStreamParser(pdf.getPage(0)).parse().filterIsInstance<Operator>()
                    operators.any { it.name == "Tr" } == expected
                }
            } }
            assertTrue(results.all { it.get(30, TimeUnit.SECONDS) })
        } finally { workers.shutdownNow() }
    }

}
