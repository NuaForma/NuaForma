package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PdfPhysicalTextBoundsTest {
    private val measure = TextMeasurer { text, style ->
        TextMetrics(text.length * style.size * .5f, style.size + style.baselineShift, style.size * .4f - style.baselineShift)
    }

    private fun paragraph(text: String = "Hgjpq", size: Float = 20f, line: Float = 10f, shift: Float = 0f) =
        PdfParagraph(listOf(PdfTextRun(text, PdfTextStyle("regular", size, baselineShift = shift))),
            format = PdfParagraphFormat(exactLineHeight = line))

    private fun layout(page: PdfPage, paragraphs: List<PdfParagraph>) =
        DocumentLayouter.plain(DocumentTransforms.normalized(PdfDocument(page, paragraphs, PdfFlow())), measure, PdfRenderControl()).pages

    private fun overflow(action: () -> Unit) {
        assertEquals(PdfFailureCode.CONTENT_OVERFLOW, assertThrows(PdfRenderException::class.java, action).code)
    }

    @Test
    fun `virtual first-line ink may extend above zero when the physical page has room`() {
        val p = paragraph()
        val measured = FlowLayouter.paragraph(p, 120f, measure)
        val text = measured.lines.single().filterIsInstance<PositionedText>().single()
        assertTrue(text.requiresPhysicalBounds)
        assertTrue(text.baseline - measure.measure(text.text, text.style).ascent < 0)
        val page = PdfPage(200f, 200f, PdfMargins(40f, 40f, 40f, 40f))
        val placed = layout(page, listOf(p)).single().elements.filterIsInstance<PositionedText>().single()
        assertEquals(48f, placed.baseline)
    }

    @Test
    fun `physical top and bottom overflow remain explicit failures`() {
        val page = PdfPage(200f, 100f, PdfMargins(0f, 20f, 0f, 20f))
        overflow { layout(page, listOf(paragraph())) }
        overflow { layout(page, listOf(paragraph("Before", 8f, 90f), paragraph())) }
        val pageWithRoom = PdfPage(200f, 200f, PdfMargins(40f, 20f, 40f, 20f))
        assertEquals(1, layout(pageWithRoom, listOf(paragraph(shift = 15f))).size)
        overflow { layout(page.copy(margins = PdfMargins(20f, 20f, 0f, 20f)), listOf(paragraph(shift = 15f))) }
    }

    @Test
    fun `unpainted blank lines need logical room but no invented glyph bounds`() {
        val page = PdfPage(200f, 100f, PdfMargins(0f, 20f, 0f, 20f))
        assertEquals(1, layout(page, listOf(paragraph(" "))).size)
        overflow { layout(page, listOf(paragraph(" ", line = 101f))) }
    }

    @Test
    fun `single-page foundation ink checks remain unchanged`() {
        val page = PdfPage(200f, 200f, PdfMargins(40f, 40f, 40f, 40f))
        overflow { PageLayouter.layout(PdfDocument(page, listOf(paragraph())), measure) }
    }

    @Test
    fun `physical failures retain exact measurements and reject nonfinite ink`() {
        val page = PdfPage(200f, 100f, PdfMargins(0f, 20f, 0f, 20f))

        fun check(baseline: Float, metrics: TextMetrics, expected: String) {
            val text = PositionedText("Hgjpq", PdfTextStyle("regular", 20f), 20f, baseline, requiresPhysicalBounds = true)
            val error = assertThrows(PdfRenderException::class.java) {
                PhysicalTextBounds.check(PageLayout(page, listOf(text)), TextMeasurer { _, _ -> metrics })
            }
            assertEquals(PdfFailureCode.CONTENT_OVERFLOW, error.code)
            assertEquals(expected, error.message)
        }

        check(8f, TextMetrics(10f, 20f, 8f), "Text ascent exceeds physical page top; required=20.0 pt; available=8.0 pt")
        check(98f, TextMetrics(10f, 20f, 8f), "Text descent exceeds physical page bottom; required=8.0 pt; available=2.0 pt")
        check(40f, TextMetrics(10f, Float.NaN, 8f), "Text ink bounds are not finite")
    }

    @Test
    fun `physical ink measurement preserves qualified Hebrew direct glyph encoding`() {
        val style = PdfTextStyle("regular", 12f)
        val paragraph = PdfParagraph(listOf(PdfTextRun("אבג דהו", style)),
            spaceAfter = 0f, format = PdfParagraphFormat(alignment = PdfAlignment.RIGHT, exactLineHeight = 16f,
                textDirection = PdfTextDirection.RIGHT_TO_LEFT))
        val page = PdfPage(240f, 200f, PdfMargins(40f, 20f, 40f, 20f))
        Loader.loadPDF(PdfEngine().render(PdfDocument(page, listOf(paragraph), PdfFlow()), FoundationLetter.fonts())).use { pdf ->
            assertEquals(1, pdf.numberOfPages)
            val text = PDFTextStripper().getText(pdf)
            for (letter in "אבגדהו") assertTrue(text.contains(letter), text)
        }
    }

    @Test
    fun `physical lower edge follows the printed glyphs including shifted and decorated ink`() {
        val page = PdfPage(240f, 100f, PdfMargins(40f, 20f, 0f, 20f))
        val before = paragraph("Before", 8f, 50f).copy(spaceAfter = 0f)

        fun render(value: String, style: PdfTextStyle): ByteArray {
            val last = PdfParagraph(listOf(PdfTextRun(value, style)), format = PdfParagraphFormat(exactLineHeight = 10f))
            return PdfEngine().render(PdfDocument(page, listOf(before, last), PdfFlow()), FoundationLetter.fonts())
        }

        val plain = PdfTextStyle("regular", 12f)
        Loader.loadPDF(render("H", plain)).use { pdf ->
            assertEquals(1, pdf.numberOfPages)
            assertTrue(PDFTextStripper().getText(pdf).contains("H"))
        }
        overflow { render("g", plain) }
        overflow { render("H", plain.copy(baselineShift = -4f)) }
        overflow { render(" ", plain.copy(size = 36f, underline = true)) }
        overflow { render("g", plain.withSyntheticBold(true)) }
    }

    @Test
    fun `real fonts render exact-leading text only when its final ink fits`() {
        val page = PdfPage(240f, 200f, PdfMargins(40f, 20f, 40f, 20f))
        Loader.loadPDF(PdfEngine().render(PdfDocument(page, listOf(paragraph("ÅHgjpq", 18f)), PdfFlow()), FoundationLetter.fonts())).use { pdf ->
            assertEquals(1, pdf.numberOfPages)
            assertTrue(PDFTextStripper().getText(pdf).contains("ÅHgjpq"))
        }
        val edge = PdfPage(240f, 200f, PdfMargins(0f, 20f, 0f, 20f))
        overflow { PdfEngine().render(PdfDocument(edge, listOf(paragraph(size = 72f, line = 1f)), PdfFlow()), FoundationLetter.fonts()) }
    }
}
