package dev.nuaforma.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PdfMinimumLineHeightTest {
    private val fonts = FoundationLetter.fonts()

    private fun paragraph(size: Float, floor: Float) = PdfParagraph(
        listOf(PdfTextRun("Q01 Q02 Q03", PdfTextStyle("regular", size))), 0f, 0f,
        PdfParagraphFormat(rightIndent = 220f)).withMinimumLineHeight(floor)

    private fun render(paragraphs: List<PdfParagraph>) = PdfEngine().render(
        PdfDocument(PdfPage(300f, 500f, PdfMargins(20f, 20f, 20f, 20f)), paragraphs, PdfFlow()), fonts)

    @Test
    fun `minimum spacing raises the baseline and allows larger natural text boxes`() {
        for ((size, floor, height, first) in listOf(
            listOf(10f, 14f, 14f, 31.88f), listOf(20f, 14f, 22.998f, 38.76f),
            listOf(10f, 6f, 11.499f, 29.38f), listOf(10f, 28f, 28f, 45.88f))) {
            Loader.loadPDF(render(listOf(paragraph(size, floor)))).use { pdf ->
                val y = mutableListOf<Float>()
                object : PDFTextStripper() {

                    override fun processTextPosition(position: TextPosition) {
                        if (position.unicode == "Q") y += position.yDirAdj
                        super.processTextPosition(position)
                    }

                }.getText(pdf)
                assertEquals(3, y.size)
                assertEquals(height, y[1] - y[0], .02f)
                assertEquals(height, y[2] - y[1], .02f)
                assertEquals(first, y[0], .03f)
            }
        }
    }

    @Test
    fun `copy transformations retain the immutable floor and policies cannot conflict`() {
        val original = paragraph(10f, 14f)
        val copied = original.copy(spaceAfter = 3f).withSpacing(PdfParagraphSpacing()).withPageOffset(null)
            .withMinimumLineStyle(PdfTextStyle("regular", 10f))
        assertEquals(14f, copied.minimumLineHeight)
        assertNull(copied.withMinimumLineHeight(null).minimumLineHeight)
        assertEquals(14f, original.minimumLineHeight)
        for (height in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY, 14_401f)) {
            assertThrows(IllegalArgumentException::class.java) { original.withMinimumLineHeight(height) }
        }
        for (format in listOf(PdfParagraphFormat(exactLineHeight = 14f),
            PdfParagraphFormat(automaticLineSpacing = PdfAutomaticLineSpacing()))) {
            assertThrows(IllegalArgumentException::class.java) { original.copy(format = format) }
        }
        assertNotNull(original.withMinimumLineHeight(0f))
    }

    @Test
    fun `minimum boxes survive page fields tables frames and note reconstruction`() {
        val style = PdfTextStyle("regular", 10f)

        fun p(runs: List<PdfInline>) = PdfParagraph(runs, 0f, 0f).withMinimumLineHeight(28f)

        fun text(value: String) = p(listOf(PdfTextRun(value, style)))

        val note = PdfNote("foot", PdfNoteKind.ENDNOTE, PdfListValue("notes", 0, 1, "1"),
            listOf(p(listOf(PdfNoteLabel(style), PdfTextRun(" N0", style))), text("N1")), PdfNoteSeparator(width = 100f))
        val header = PdfPageBand(5f, 60f, listOf(p(listOf(PdfTextRun("H0 ", style), PdfPageNumber(style))), text("H1")))
        val table = PdfTable(listOf(150f), listOf(PdfTableRow(listOf(PdfTableCell(listOf(text("T0"), text("T1")))))))
        val frame = PdfTextBox(200f, 250f, 90f, 70f, listOf(text("F0"), text("F1")), PdfBoxAnchor.PAGE)
        val document = PdfDocument(PdfPage(400f, 600f, PdfMargins(top = 100f, right = 30f, bottom = 100f, left = 30f)),
            listOf(p(listOf(PdfTextRun("B0", style), PdfNoteCall("call", "foot", style))), text("B1"), table, frame),
            PdfFlow(header = header), notes = listOf(note))
        Loader.loadPDF(PdfEngine().render(document, fonts)).use { pdf ->
            assertEquals(1, pdf.numberOfPages)
            val baselines = mutableMapOf<String, MutableList<Float>>()
            object : PDFTextStripper() {

                override fun processTextPosition(position: TextPosition) {
                    if (position.unicode in setOf("H", "B", "T", "F", "N"))
                        baselines.getOrPut(position.unicode) { mutableListOf() } += position.yDirAdj
                    super.processTextPosition(position)
                }

            }.getText(pdf)
            for (prefix in listOf("H", "B", "T", "F", "N")) {
                val y = baselines.getValue(prefix)
                assertEquals(2, y.size, prefix)
                assertEquals(28f, y[1] - y[0], .02f, prefix)
            }
            assertTrue(PDFTextStripper().getText(pdf).contains("H0 1"))
        }
    }

    @Test
    fun `minimum boxes paginate without dropping or repeating text`() {
        val paragraphs = (1..40).map { PdfParagraph(listOf(PdfTextRun("Item$it", PdfTextStyle("regular", 10f))),
            0f, 0f).withMinimumLineHeight(28f) }
        Loader.loadPDF(render(paragraphs)).use { pdf ->
            assertEquals(3, pdf.numberOfPages)
            val text = PDFTextStripper().getText(pdf)
            for (id in 1..40) assertEquals(1, Regex("Item$id(?![0-9])").findAll(text).count())
        }
    }

}
