package dev.nuaforma.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PdfTextBoxExternalLinkTest {
    private val page = PdfPage(320f, 240f, PdfMargins(20f, 20f, 20f, 20f))
    private val link = PdfLink.External("https://example.test/frame-link")
    private val styledLink = PdfTextRun("Embedded link", PdfTextStyle("regular", 10f), PdfTextNavigation(link))

    @Test
    fun `annotations inside a fixed text box follow its physical translation`() {
        fun bounds(x: Float, y: Float): List<Float> {
            Loader.loadPDF(render(frame(x, y))).use { doc ->
                val annotations = doc.getPage(0).annotations.filterIsInstance<PDAnnotationLink>()
                annotations.forEach { assertEquals(link.uri, (it.action as PDActionURI).uri) }
                val rectangles = annotations.map { it.rectangle }
                return listOf(rectangles.minOf { it.lowerLeftX }, rectangles.minOf { it.lowerLeftY },
                    rectangles.maxOf { it.upperRightX }, rectangles.maxOf { it.upperRightY })
            }
        }

        val original = bounds(40f, 45f)
        val moved = bounds(53f, 64f)
        val expectedDelta = listOf(13f, -19f, 13f, -19f)
        for (index in original.indices) assertEquals(expectedDelta[index], moved[index] - original[index], .01f)
    }

    @Test
    fun `rotation keeps the existing explicit navigation rejection`() {
        val failure = assertThrows(PdfRenderException::class.java) { render(frame(60f, 60f, 15f)) }
        assertEquals(PdfFailureCode.UNSUPPORTED_TEXT, failure.code)
    }

    private fun frame(x: Float, y: Float, rotation: Float = 0f) =
        PdfTextBox(x, y, 150f, 40f, listOf(PdfParagraph(listOf(styledLink),
            format = PdfParagraphFormat(exactLineHeight = 16f))), PdfBoxAnchor.PAGE, rotation)

    private fun render(frame: PdfTextBox): ByteArray =
        PdfEngine().render(PdfDocument(page, listOf(frame), PdfFlow()), FoundationLetter.fonts())
}
