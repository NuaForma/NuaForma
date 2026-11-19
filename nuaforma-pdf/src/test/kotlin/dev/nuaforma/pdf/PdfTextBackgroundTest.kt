package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PdfTextBackgroundTest {
    private val yellow = PdfColor(255, 255, 0)
    private val style = PdfTextStyle("regular", 10f).withBackground(yellow)

    @Test
    fun `background is immutable and retained by existing copy and decoration methods`() {
        val plain = PdfTextStyle("regular", 10f)
        assertNull(plain.background)
        assertEquals(plain, style.withBackground(null))
        assertEquals(plain.hashCode(), style.withBackground(null).hashCode())
        for (copy in listOf(style.copy(size = 12f), style.withCharacterSpacing(1f), style.withSyntheticBold(true),
            style.withFontFeatures(PdfFontFeatures()), style.withStrikethrough(true))) assertEquals(yellow, copy.background)
        assertNotEquals(plain, style)
        assertTrue(style.toString().contains("background="))
    }

    @Test
    fun `background follows the entire physical line including superscript and justified gaps`() {
        val page = PdfPage(180f, 180f, PdfMargins(20f, 20f, 20f, 20f))
        val superscript = style.copy(baselineShift = 4f)
        val paragraph = PdfParagraph(listOf(PdfTextRun("AA BB CC DD EE FF GG", superscript)), 0f, 0f,
            PdfParagraphFormat(PdfAlignment.JUSTIFY, exactLineHeight = 30f))
        val measured = PageLayouter.layout(PdfDocument(page, listOf(paragraph)), TextMeasurer { text, s ->
            TextMetrics(text.length * 8f, 10f + s.baselineShift, 2f - s.baselineShift)
        })
        val texts = measured.elements.filterIsInstance<PositionedText>()
        assertTrue(texts.any { it.trailingDecorationWidth != null })
        for (text in texts) {
            assertEquals(30f, text.background!!.height)
            assertEquals(24f, text.background.ascent)
            val rectangle = TextBackgrounds.rectangle(text, page.width, page.height)!!
            assertEquals(text.baseline - 24f, rectangle.top)
        }
    }

    @Test
    fun `opaque backgrounds are artifacts without replacing embedded searchable text`() {
        val paragraph = PdfParagraph(listOf(PdfTextRun("VISIBLE", style)), 0f, 0f, PdfParagraphFormat(exactLineHeight = 30f))
        val document = PdfDocument(PdfPage(200f, 150f, PdfMargins(20f, 20f, 20f, 20f)), listOf(paragraph))
        Loader.loadPDF(PdfEngine().render(document, FoundationLetter.fonts())).use { pdf ->
            assertTrue(PDFTextStripper().getText(pdf).contains("VISIBLE"))
            val image = PDFRenderer(pdf).renderImageWithDPI(0, 72f)
            assertEquals(0xFFFF00, image.getRGB(21, 21) and 0xFFFFFF)
            assertEquals(0xFFFFFF, image.getRGB(100, 21) and 0xFFFFFF)
            val stream = pdf.getPage(0).contents.use { it.readBytes().decodeToString() }
            assertTrue(Regex("/Artifact\\s+BMC").containsMatchIn(stream))
            assertTrue(pdf.getPage(0).resources.fontNames.all { pdf.getPage(0).resources.getFont(it).isEmbedded })
        }
    }

    @Test
    fun `translated rectangles retain their baseline offset and reject each physical edge`() {
        val text = PositionedText("X", style, 10f, 20f, background = TextBackground(10f, 8f, 12f))
        val moved = FlowLayouter.translate(text, 5f, 7f) as PositionedText
        assertEquals(PositionedRectangle(15f, 19f, 10f, 12f, yellow, null), TextBackgrounds.rectangle(moved, 100f, 100f))
        for (bad in listOf(text.copy(x = -1f), text.copy(x = 95f), text.copy(baseline = 1f), text.copy(baseline = 99f))) {
            val failure = assertThrows(PdfRenderException::class.java) { TextBackgrounds.rectangle(bad, 100f, 100f) }
            assertEquals(PdfFailureCode.CONTENT_OVERFLOW, failure.code)
            assertTrue(failure.message!!.contains("required="))
        }
        assertNull(TextBackgrounds.rectangle(text.copy(style = style.withBackground(null)), 100f, 100f))
    }

}
