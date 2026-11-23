package dev.nuaforma.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PdfAnchoredImageLinkTest {
    private val page = PdfPage(260f, 220f, PdfMargins(30f, 20f, 30f, 20f))
    private val style = PdfTextStyle("regular", 10f)
    private val link = PdfLink.External("https://example.test/logo")
    private val image = PdfImage(FoundationLetter.resource("/fixtures/nuaforma-icon-transparent.png"),
        60f, 20f, PdfImageCrop(left = .2f))

    @Test
    fun `a cropped fixed picture links only its visible rectangle in body and footer`() {
        val fixed = PdfAnchoredImage(image, 150f, 72f, PdfBoxAnchor.PAGE, PdfImageWrap.NONE).withLink(link)
        assertSame(link, fixed.copy().link)
        val body = paragraph("Body")
        val footer = PdfPageBand(8f, 22f, listOf(paragraph("Footer", listOf(fixed.copy(top = 190f)))))
        val cases = listOf(PdfDocument(page, listOf(paragraph("Body", listOf(fixed))), PdfFlow()) to 148f,
            PdfDocument(page, listOf(body), PdfFlow(footer = footer)) to 30f)
        for ((source, top) in cases) {
            Loader.loadPDF(PdfEngine().render(source, FoundationLetter.fonts())).use { document ->
                val annotation = document.getPage(0).annotations.single() as PDAnnotationLink
                assertEquals(link.uri, (annotation.action as PDActionURI).uri)
                assertEquals(150f, annotation.rectangle.lowerLeftX, .01f)
                assertEquals(210f, annotation.rectangle.upperRightX, .01f)
                assertEquals(top, annotation.rectangle.upperRightY, .01f)
                assertEquals(top - 20f, annotation.rectangle.lowerLeftY, .01f)
            }
        }
    }

    @Test
    fun `the link requires a page fixed image without wrap`() {
        for (placement in listOf(PdfAnchoredImage(image, 80f, 50f),
            PdfAnchoredImage(image, 80f, 50f, PdfBoxAnchor.PAGE, PdfImageWrap.RIGHT))) {
            assertThrows(IllegalArgumentException::class.java) { placement.withLink(link) }
        }
    }

    private fun paragraph(text: String, anchors: List<PdfAnchoredImage> = emptyList()) =
        PdfParagraph(listOf(PdfTextRun(text, style)), 0f, 0f, PdfParagraphFormat(exactLineHeight = 14f),
            anchoredImages = anchors)
}
