package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.*

/** Signed indentation may enter the margins, but never extend the text frame off the physical page. */
internal object ParagraphBounds {

    fun check(paragraph: PdfParagraph, page: PdfPage) {
        val format = paragraph.format
        val left = page.margins.left + minOf(format.leftIndent, format.leftIndent + format.firstLineIndent)
        val right = page.width - page.margins.right - format.rightIndent
        if (left < -.001f || right > page.width + .001f || right <= left)
            throw PdfRenderException(PdfFailureCode.CONTENT_OVERFLOW, "Paragraph indentation exceeds the physical page")
    }
}
