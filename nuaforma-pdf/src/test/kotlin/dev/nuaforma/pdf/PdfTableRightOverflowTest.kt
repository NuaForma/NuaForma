package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PdfTableRightOverflowTest {
    private val measurer = TextMeasurer { text, _ -> TextMetrics(text.length * 5f, 8f, 2f) }
    private val page = PdfPage(300f, 400f, PdfMargins(80f, 30f, 30f, 30f))

    private fun paragraph(text: String) = PdfParagraph(listOf(PdfTextRun(text, PdfTextStyle("regular", 10f))),
        format = PdfParagraphFormat(exactLineHeight = 14f))

    private fun table(width: Float = 250f, policy: PdfTableWidthPolicy = PdfTableWidthPolicy.FIXED) =
        PdfTable(listOf(width), listOf(PdfTableRow(listOf(PdfTableCell(listOf(paragraph("TABLE_END")))))),
            border = null, widthPolicy = policy, preferredWidth = PdfPreferredWidth.points(width))

    private fun document(table: PdfTable, header: Boolean = false) = if (header)
        PdfDocument(page, listOf(paragraph("BODY")), PdfFlow(header = PdfPageBand(10f, 60f, listOf(table))))
        else PdfDocument(page, listOf(table), PdfFlow())

    private fun layout(document: PdfDocument) =
        DocumentLayouter.plain(DocumentTransforms.normalized(document), measurer, PdfRenderControl()).pages

    @Test
    fun `overflow is immutable bounded and disabled by existing constructors`() {
        val source = table()
        assertEquals(0f, source.rightOverflow)
        val changed = source.withRightOverflow(12f)
        assertEquals(12f, changed.rightOverflow)
        assertEquals(0f, source.rightOverflow)
        assertEquals(source.columnWidths, changed.columnWidths)
        assertEquals(source.rows, changed.rows)
        assertEquals(source.preferredWidth, changed.preferredWidth)
        for (value in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY, 14_401f))
            assertThrows(IllegalArgumentException::class.java) { source.withRightOverflow(value) }
        assertEquals(14_400f, source.withRightOverflow(14_400f).rightOverflow)
    }

    @Test
    fun `automatic widths consume only the requested additional margin`() {
        val plain = table(policy = PdfTableWidthPolicy.AUTO_FIT)
        val allowed = plain.withRightOverflow(10f)
        assertEquals(240f, TableWidthResolver.resolve(plain, measurer, 240f).columnWidths.single(), .001f)
        val resolved = TableWidthResolver.resolve(allowed, measurer, 240f)
        assertEquals(250f, resolved.columnWidths.single(), .001f)
        assertEquals(10f, resolved.rightOverflow)
        assertEquals(allowed.preferredWidth, resolved.preferredWidth)
    }

    @Test
    fun `normalization and natural width resolution preserve the allowance`() {
        val source = table(policy = PdfTableWidthPolicy.FIT_CONTENTS).withRightOverflow(10f)
        val normalized = DocumentTransforms.normalized(document(source)).blocks.single() as PdfTable
        assertEquals(10f, normalized.rightOverflow)
        val resolved = TableWidthResolver.resolve(normalized, measurer, 240f)
        assertEquals(10f, resolved.rightOverflow)
        assertEquals(source.preferredWidth, resolved.preferredWidth)
    }

    @Test
    fun `body and page bands accept only explicitly requested margin space`() {
        for (header in listOf(false, true)) for (policy in listOf(PdfTableWidthPolicy.FIXED, PdfTableWidthPolicy.AUTO_FIT)) {
            val source = table(policy = policy)
            if (policy == PdfTableWidthPolicy.FIXED) assertThrows(PdfRenderException::class.java) {
                layout(document(source, header))
            }
            val layout = layout(document(source.withRightOverflow(10f), header))
            assertEquals(1, layout.size)
            assertTrue(layout.single().elements.filterIsInstance<PositionedText>().any { it.text.contains("TABLE_END") })
        }
    }

    @Test
    fun `large allowances never bypass the physical page edge in body or bands`() {
        for (header in listOf(false, true)) for (policy in listOf(PdfTableWidthPolicy.FIXED, PdfTableWidthPolicy.AUTO_FIT)) {
            val source = table(280f, policy).withRightOverflow(100f)
            assertEquals(PdfFailureCode.CONTENT_OVERFLOW, assertThrows(PdfRenderException::class.java) {
                layout(document(source, header))
            }.code)
        }
        val stroked = PdfTable(listOf(270f), table().rows, border = PdfBorder(1f, PdfColor(0, 0, 0))).withRightOverflow(30f)
        assertThrows(PdfRenderException::class.java) { layout(document(stroked)) }
    }

    @Test
    fun `nested tables remain bounded by the containing cell`() {
        val child = table(110f).withRightOverflow(100f)
        val parent = PdfTable(listOf(100f), listOf(PdfTableRow(listOf(PdfTableCell(listOf(child))))), border = null)
        assertThrows(PdfRenderException::class.java) { TableLayouter.measure(parent, measurer, 240f) }
    }

    @Test
    fun `actual PDF retains body and band text with embedded fonts`() {
        val source = table().withRightOverflow(10f)
        val document = PdfDocument(page, listOf(source), PdfFlow(header = PdfPageBand(10f, 60f, listOf(source))))
        Loader.loadPDF(PdfEngine().render(document, FoundationLetter.fonts())).use { pdf ->
            assertEquals(1, pdf.numberOfPages)
            assertEquals(2, Regex("TABLE_END").findAll(PDFTextStripper().getText(pdf)).count())
            assertTrue(pdf.getPage(0).resources.fontNames.all { pdf.getPage(0).resources.getFont(it).isEmbedded })
        }
    }
}
