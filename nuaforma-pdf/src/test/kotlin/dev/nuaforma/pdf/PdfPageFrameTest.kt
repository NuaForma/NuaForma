package dev.nuaforma.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.cos.COSNumber
import org.apache.pdfbox.pdfparser.PDFStreamParser
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PdfPageFrameTest {
    private val color = PdfColor(127, 33, 73)
    private val offsets = PdfMargins(24f, 6f, 12f, 18f)
    private val width = .5f
    private val page = PdfPage(612f, 792f, PdfMargins(72f, 72f, 72f, 72f))

    private fun paragraph(label: String, pageBreakBefore: Boolean = false) =
        PdfParagraph(listOf(PdfTextRun(label, PdfTextStyle("regular", 11f))),
            format = PdfParagraphFormat(exactLineHeight = 18f, pageBreakBefore = pageBreakBefore))

    private fun sequence(first: String, second: String) =
        PdfPageSequence(page, listOf(paragraph(first), paragraph(second, true)), PdfFlow(),
            PdfPageNumbering(start = 3))

    @Test
    fun `first and later physical pages select the solid frame independently of sequence numbering`() {
        val first = sequence("Alpha", "Bravo").withPageFrame(PdfPageFrame(offsets, PdfBorder(width, color), PdfPageFrameDisplay.FIRST))
        val second = sequence("Charlie", "Delta").withPageFrame(PdfPageFrame(offsets, PdfBorder(width, color), PdfPageFrameDisplay.EXCEPT_FIRST))
        val bytes = PdfEngine().render(PdfDocument(listOf(first, second)), FoundationLetter.fonts())
        Loader.loadPDF(bytes).use { doc ->
            assertEquals(4, doc.numberOfPages)
            assertEquals(listOf("Alpha", "Bravo", "Charlie", "Delta"),
                PDFTextStripper().getText(doc).lineSequence().filter { it.isNotBlank() }.toList())
            val expected = listOf(18.25f, 12.25f, 587.5f, 755.5f)
            for (index in 0..3) {
                val tokens = PDFStreamParser(doc.getPage(index)).parse()
                val rectangles = tokens.mapIndexedNotNull { tokenIndex, token ->
                    if (token is Operator && token.name == "re") (tokenIndex - 4 until tokenIndex).map { (tokens[it] as COSNumber).floatValue() }
                    else null
                }
                assertEquals(if (index in setOf(0, 3)) listOf(expected) else emptyList(), rectangles)
            }
        }
    }

    @Test
    fun `the immutable frame can be removed and must have a physical interior`() {
        val original = sequence("Alpha", "Bravo")
        val frame = PdfPageFrame(offsets, PdfBorder(width, color))
        val framed = original.withPageFrame(frame)
        assertNull(original.pageFrame)
        assertEquals(frame, framed.pageFrame)
        assertNull(framed.withPageFrame(null).pageFrame)
        assertThrows(IllegalArgumentException::class.java) {
            original.withPageFrame(PdfPageFrame(PdfMargins(0f, 300f, 0f, 312f), frame.border))
        }
    }
}
