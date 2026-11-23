package dev.nuaforma.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PdfLinkedTabTest {
    private val page = PdfPage(350f, 220f, PdfMargins(25f, 25f, 25f, 25f))
    private val style = PdfTextStyle("regular", 11f)
    private val destination = PdfDestination("After")
    private val target = PdfParagraph(listOf(PdfTextRun("Destination", style,
        PdfTextNavigation(destinations = listOf(destination)))), format = PdfParagraphFormat(exactLineHeight = 16f))

    @Test
    fun tabOnlyLinkCoversTheFullResolvedGap() {
        for (leader in listOf(PdfTabLeader.NONE, PdfTabLeader.DOT)) {
            val control = PdfTab(style).withNavigation(PdfTextNavigation(PdfLink.Internal(destination.name)))
            val paragraph = PdfParagraph(listOf(PdfTextRun("Before", style), control, PdfTextRun("After", style)),
                format = PdfParagraphFormat(exactLineHeight = 16f,
                    tabs = PdfTabSettings(listOf(PdfTabStop(150f, PdfTabAlignment.LEFT, leader)))))
            val bytes = PdfEngine().render(PdfDocument(page, listOf(paragraph, target), PdfFlow()), FoundationLetter.fonts())
            Loader.loadPDF(bytes).use { pdf ->
                val annotation = pdf.getPage(0).annotations.single() as PDAnnotationLink
                assertEquals(destination.name, (annotation.destination as PDNamedDestination).namedDestination)
                assertTrue(annotation.rectangle.lowerLeftX in 40f..70f)
                assertEquals(175f, annotation.rectangle.upperRightX, .02f)
            }
        }
    }

    @Test
    fun copiedTabPreservesLinkAndCannotOwnDestination() {
        val navigation = PdfTextNavigation(PdfLink.Internal(destination.name))
        val tab = PdfTab(style).withNavigation(navigation)
        assertSame(navigation, tab.copy(style.copy(underline = true)).navigation)
        assertNull(PdfTab(style).navigation)
        assertThrows(IllegalArgumentException::class.java) {
            tab.withNavigation(PdfTextNavigation(destinations = listOf(destination)))
        }
    }
}
