package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.DocumentLayouter
import dev.nuaforma.pdf.internal.PositionedText
import dev.nuaforma.pdf.internal.TextMeasurer
import dev.nuaforma.pdf.internal.TextMetrics
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PdfWebAddressWrappingTest {
    private val regular = PdfTextStyle("regular", 12f)
    private val linkStyle = regular.copy(underline = true)
    private val tailStyle = regular.copy(color = PdfColor(80, 30, 20))
    private val measurer = TextMeasurer { text, _ -> TextMetrics(text.length * 6f, 12f, 3f) }
    private val target = PdfTextNavigation(PdfLink.External("https://example.test/short-part"), logicalId = "link")
    private val firstPart = "https://example.test/short-"
    private val suffix = "-final#piece"

    @Test
    fun keepsStylesAndNavigationAcrossAnExistingHyphen() {
        val result = layout(192f, listOf(PdfTextRun("Lead ", regular),
            PdfTextRun(firstPart + "part", linkStyle, target), PdfTextRun(suffix, tailStyle)))
        assertEquals(listOf("Lead " + firstPart, "part" + suffix), lines(result))
        val linked = result.filter { it.navigation?.link != null }
        assertEquals(listOf(firstPart, "part"), linked.map { it.text })
        assertTrue(linked.all { it.navigation == target && it.style == linkStyle })
        assertEquals(listOf(5, 5 + firstPart.length), linked.map { it.logicalOffset })
        assertEquals(tailStyle, result.last().style)
        assertNull(result.last().navigation)
    }

    @Test
    fun retainsFittingAddressesAndBreaksAfterFragmentSeparator() {
        val address = "https://example.test/#longfragment"
        val run = PdfTextRun(address, regular)
        assertEquals(listOf(address), lines(layout(240f, listOf(run))))
        assertEquals(listOf("https://example.test/#", "longfragment"), lines(layout(150f, listOf(run))))
    }

    @Test
    fun reportsOverflowWithoutAQualifiedBreak() {
        val ordinary = "non-url-word-with-visible-hyphen"
        val bad = "https://example.test/firstlongpath-last"
        val ordinaryError = assertThrows(PdfRenderException::class.java) {
            layout(144f, listOf(PdfTextRun(ordinary, regular)))
        }
        assertEquals(PdfFailureCode.CONTENT_OVERFLOW, ordinaryError.code)
        val addressError = assertThrows(PdfRenderException::class.java) {
            layout(144f, listOf(PdfTextRun(bad, regular)))
        }
        assertEquals(PdfFailureCode.CONTENT_OVERFLOW, addressError.code)
        val minimumWidth = (bad.lastIndexOf('-') + 1) * 6f
        assertTrue(addressError.message.orEmpty().endsWith("required=$minimumWidth pt; available=144.0 pt"), addressError.message)
    }

    private fun layout(width: Float, runs: List<PdfTextRun>): List<PositionedText> {
        val page = PdfPage(width + 60f, 300f, PdfMargins(30f, 30f, 30f, 30f))
        val paragraph = PdfParagraph(runs, format = PdfParagraphFormat(exactLineHeight = 20f),
            semantics = PdfBlockSemantics("address"))
        val document = PdfDocument(page, listOf(paragraph), PdfFlow())
        return DocumentLayouter.plain(document, measurer, PdfRenderControl()).pages.single().elements
            .filterIsInstance<PositionedText>()
    }

    private fun lines(items: List<PositionedText>) =
        items.groupBy { it.baseline }.values.map { line -> line.joinToString("") { it.text } }
}
