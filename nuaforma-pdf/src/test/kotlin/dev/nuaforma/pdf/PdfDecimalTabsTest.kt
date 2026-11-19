package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.PageLayouter
import dev.nuaforma.pdf.internal.PositionedText
import dev.nuaforma.pdf.internal.TextMeasurer
import dev.nuaforma.pdf.internal.TextMetrics
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PdfDecimalTabsTest {
    private val style = PdfTextStyle("regular", 12f)
    private val page = PdfPage(400f, 250f, PdfMargins(30f, 40f, 30f, 40f))
    private val measurer = TextMeasurer { text, style -> TextMetrics(text.length * style.size / 2, style.size * .8f, style.size * .2f) }

    private fun layout(runs: List<PdfInline>, settings: PdfTabSettings): List<PositionedText> {
        val format = PdfParagraphFormat(exactLineHeight = 24f, tabs = settings)
        val paragraph = PdfParagraph(listOf(PdfTab(style)) + runs, 0f, 0f, format)
        return PageLayouter.layout(PdfDocument(page, listOf(paragraph)), measurer).elements.filterIsInstance<PositionedText>()
    }

    @Test
    fun `numeric fields align at the Word qualified boundary without changing their text`() {
        val settings = PdfTabSettings(listOf(PdfTabStop(120f, PdfTabAlignment.DECIMAL)), 36f, ",").withDecimalPolicy(PdfDecimalTabPolicy.FIRST_NUMBER, ".")
        val cases = mapOf("12,50" to 2, "12.50" to 5, "USD 12,50" to 6, "1.234,50" to 5,
            "1,234.50" to 1, "ABC 12X" to 6, "ABC" to 3, "L01_12,50_END" to 3,
            "-12,50" to 3, "(12,50)" to 3, "12 345,50" to 2, "AB12CD34" to 4, "12:50" to 2,
            "A.B 12,5" to 6, ",50" to 0, "ABC, DEF" to 3, "$ ,50" to 2, "A,B 12,50" to 1, "12." to 3, "12..34" to 6, "12. X" to 3, "," to 0)
        for ((value, prefix) in cases) {
            val found = layout(listOf(PdfTextRun(value, style)), settings)
            assertEquals(160f - prefix * 6, found.first { it.text.isNotBlank() }.x, .001f, value)
            assertEquals(value.replace(" ", ""), found.joinToString("") { it.text }.replace(" ", ""), value)
        }
    }

    @Test
    fun `the original explicit separator policy stays independent of the numeric policy`() {
        val value = "L01_12,50_END"
        val settings = PdfTabSettings(listOf(PdfTabStop(120f, PdfTabAlignment.DECIMAL)), 36f, ",")
        val found = layout(listOf(PdfTextRun(value, style)), settings)
        assertEquals(124f, found.first { it.text.isNotBlank() }.x, .001f)
    }

    @Test
    fun `the implicit numeric boundary retains physical advances across differently styled spans`() {
        val settings = PdfTabSettings(listOf(PdfTabStop(120f, PdfTabAlignment.DECIMAL)), 36f, ",").withDecimalPolicy(PdfDecimalTabPolicy.FIRST_NUMBER)
        val small = PdfTextStyle("regular", 10f)
        val large = PdfTextStyle("regular", 20f)
        val found = layout(listOf(PdfTextRun("AB", small), PdfTextRun("12", large), PdfTextRun("CD", small)), settings)
        assertEquals(130f, found.single { it.text == "AB" }.x, .001f)
        assertEquals(140f, found.single { it.text == "12" }.x, .001f)
        assertEquals(160f, found.single { it.text == "CD" }.x, .001f)
    }

    @Test
    fun `grouping is explicit and cannot introduce ambiguous numeric or control separators`() {
        for (value in listOf("", "..", ",", "1", "\n")) assertThrows(IllegalArgumentException::class.java) {
            PdfTabSettings(emptyList(), 36f, ",").withDecimalPolicy(PdfDecimalTabPolicy.FIRST_NUMBER, value)
        }
        val settings = PdfTabSettings(listOf(PdfTabStop(120f, PdfTabAlignment.DECIMAL)), 36f, ",").withDecimalPolicy(PdfDecimalTabPolicy.FIRST_NUMBER)
        val found = layout(listOf(PdfTextRun("12.50", style)), settings)
        assertEquals(148f, found.first { it.text.isNotBlank() }.x, .001f)
    }

}
