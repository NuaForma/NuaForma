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
    fun oversizedSimplePathBreaksAtExistingCharacterBoundaries() {
        val address = "https://example.test/letters/0123456789.pdf"
        val plain = layout(150f, listOf(PdfTextRun(address, regular)))
        assertEquals(address.chunked(25), lines(plain))
        assertTrue(plain.all { it.navigation == null })

        val nav = PdfTextNavigation(PdfLink.External(address), logicalId = "large-link")
        val linked = layout(150f, listOf(PdfTextRun(address.take(30), linkStyle, nav),
            PdfTextRun(address.drop(30), tailStyle, nav)))
        assertEquals(address.chunked(25), lines(linked))
        assertTrue(linked.all { it.navigation == nav })
        assertEquals(listOf(0, 25, 30), linked.map { it.logicalOffset })
        assertEquals(listOf(linkStyle, linkStyle, tailStyle), linked.map { it.style })
    }

    @Test
    fun percentEncodedPathRetainsTheCompleteVisibleTextAndTarget() {
        val address = "https://example.test/ab%20cd/longpath.pdf"
        val nav = PdfTextNavigation(PdfLink.External(address), logicalId = "escaped-link")
        val linked = layout(150f, listOf(PdfTextRun(address, linkStyle, nav)))
        assertEquals(address.chunked(25), lines(linked))
        assertTrue(linked.all { it.navigation == nav })
        val malformed = address.replace("%20", "%ZZ")
        val error = assertThrows(PdfRenderException::class.java) { layout(150f, listOf(PdfTextRun(malformed, regular))) }
        assertEquals(PdfFailureCode.CONTENT_OVERFLOW, error.code)
    }

    @Test
    fun visibleLinkCanContinuePastAPreferredBreakOnTheNextLine() {
        val beginning = "https://example.test/short-"
        val tail = "abcdefghijklmnopqrstuvwxyzabcdefghijklmn-tail"
        val address = beginning + tail
        val nav = PdfTextNavigation(PdfLink.External(address), logicalId = "continuation")
        val result = layout(180f, listOf(PdfTextRun(address, linkStyle, nav)))
        assertEquals(listOf(beginning, tail.take(30), tail.drop(30)), lines(result))
        assertEquals(listOf(0, beginning.length, beginning.length + 30), result.map { it.logicalOffset })
        assertTrue(result.all { it.navigation == nav && it.style == linkStyle })
        val unrelatedTarget = PdfTextNavigation(PdfLink.External("https://example.test/other"), logicalId = "different")
        assertThrows(PdfRenderException::class.java) {
            layout(180f, listOf(PdfTextRun(address, linkStyle, unrelatedTarget)))
        }
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
