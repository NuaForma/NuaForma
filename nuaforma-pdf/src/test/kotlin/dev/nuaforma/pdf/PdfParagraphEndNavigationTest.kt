package dev.nuaforma.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PdfParagraphEndNavigationTest {
    private val page = PdfPage(270f, 200f, PdfMargins(25f, 25f, 25f, 25f))
    private val style = PdfTextStyle("regular", 10f)
    private val anchor = PdfDestination("Terminal", "Terminal bookmark")
    private val format = PdfParagraphFormat(exactLineHeight = 16f)

    @Test
    fun `terminal destinations retain page and horizontal placement without painting text`() {
        val source = PdfParagraph(listOf(PdfTextRun("First page link", style,
            PdfTextNavigation(PdfLink.Internal(anchor.name)))), format = format)
        for (text in listOf(" ", "Visible text before the bookmark")) {
            val target = PdfParagraph(listOf(PdfTextRun(text, style)), format = format.copy(pageBreakBefore = true))
            val end = target.withEndDestinations(listOf(anchor)).copy(spaceAfter = 0f)
            val bytes = PdfEngine().render(PdfDocument(page, listOf(source, end), PdfFlow()), FoundationLetter.fonts())
            Loader.loadPDF(bytes).use { pdf ->
                assertEquals(2, pdf.numberOfPages)
                val links = pdf.getPage(0).annotations.filterIsInstance<PDAnnotationLink>()
                assertTrue(links.isNotEmpty())
                assertTrue(links.all { (it.destination as PDNamedDestination).namedDestination == anchor.name })
                val resolved = pdf.documentCatalog.names.dests.names.getValue(anchor.name) as PDPageXYZDestination
                assertEquals(1, resolved.retrievePageNumber())
                if (text.isBlank()) assertEquals(25, resolved.left) else assertTrue(resolved.left > 25)
                assertEquals("Terminal bookmark", pdf.documentCatalog.documentOutline.firstChild.title)
                val actual = PDFTextStripper().getText(pdf).trim().replace(Regex("\\s+"), " ")
                assertEquals(("First page link " + text.trim()).trim(), actual)
            }
        }
    }

    @Test
    fun `run and terminal destinations share the same uniqueness rules`() {
        val run = PdfTextRun("Duplicated destination", style, PdfTextNavigation(destinations = listOf(anchor)))
        val paragraph = PdfParagraph(listOf(run), format = format).withEndDestinations(listOf(anchor))
        assertThrows(IllegalArgumentException::class.java) {
            PdfEngine().render(PdfDocument(page, listOf(paragraph)), FoundationLetter.fonts())
        }
    }
}
