package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.FlowLayouter
import dev.nuaforma.pdf.internal.PositionedText
import dev.nuaforma.pdf.internal.TextMeasurer
import dev.nuaforma.pdf.internal.TextMetrics
import dev.nuaforma.pdf.internal.TableLayouter
import dev.nuaforma.pdf.internal.PositionedStroke
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class PdfMixedNaturalLinesTest {
    private val measurer = TextMeasurer { text, style ->
        TextMetrics(text.length * style.size, .8f * style.size, .2f * style.size)
    }

    private fun mixed(prefix: String = "Q") = PdfParagraph(listOf(
        PdfTextRun("${prefix}1 ", PdfTextStyle("regular", 10f)),
        PdfTextRun("${prefix}2 ", PdfTextStyle("regular", 20f)),
        PdfTextRun("${prefix}3", PdfTextStyle("regular", 10f))), 0f, 0f,
        PdfParagraphFormat(automaticLineSpacing = PdfAutomaticLineSpacing(), widowControl = false))

    @Test
    fun `wrapped lines retain their own ascent descent and height`() {
        val measured = FlowLayouter.paragraph(mixed(), 40f, measurer)
        assertEquals(listOf(10f, 20f, 10f), measured.heights)
        assertEquals(40f, measured.height())
        assertEquals(30f, measured.height(0, 2))
        assertEquals(listOf(8f, 16f, 8f), measured.lines.map { line -> line.filterIsInstance<PositionedText>().first().baseline })
    }

    @Test
    fun `paragraph mark minimum and atLeast floor apply to each actual line`() {
        val p = mixed().withMinimumLineStyle(PdfTextStyle("regular", 12f))
        assertEquals(listOf(12f, 20f, 12f), FlowLayouter.paragraph(p, 40f, measurer).heights)
        val minimum = p.copy(format = PdfParagraphFormat(widowControl = false)).withMinimumLineHeight(18f)
        val measured = FlowLayouter.paragraph(minimum, 40f, measurer)
        assertEquals(listOf(18f, 20f, 18f), measured.heights)
        for ((index, expected) in listOf(15.6f, 16f, 15.6f).withIndex())
            assertEquals(expected, measured.lines[index].filterIsInstance<PositionedText>().first().baseline, .00001f)
    }

    @Test
    fun `automatic multiples and exact line boxes preserve their policies`() {
        val doubled = mixed().copy(format = PdfParagraphFormat(automaticLineSpacing = PdfAutomaticLineSpacing(2f)))
        assertEquals(listOf(20f, 40f, 20f), FlowLayouter.paragraph(doubled, 40f, measurer).heights)
        val exact = mixed().copy(format = PdfParagraphFormat(exactLineHeight = 32f))
        assertEquals(listOf(32f, 32f, 32f), FlowLayouter.paragraph(exact, 40f, measurer).heights)
    }

    @Test
    fun `body pagination uses actual heights instead of one paragraph maximum`() {
        val page = PdfPage(80f, 60f, PdfMargins(10f, 20f, 10f, 20f))
        val pages = FlowLayouter.layout(PdfDocument(page, listOf(mixed(), mixed("R")), PdfFlow()), measurer)
        assertEquals(2, pages.size)
        for ((index, prefix) in listOf("Q", "R").withIndex()) {
            val texts = pages[index].elements.filterIsInstance<PositionedText>()
            assertEquals(listOf("${prefix}1", "${prefix}2", "${prefix}3"), texts.map { it.text })
            assertEquals(listOf(18f, 36f, 48f), texts.map { it.baseline })
        }
    }

    @Test
    fun `mixed bidi natural boxes retain their unqualified boundary`() {
        val p = mixed().copy(format = PdfParagraphFormat(automaticLineSpacing = PdfAutomaticLineSpacing(),
            textDirection = PdfTextDirection.RIGHT_TO_LEFT))
        val error = assertThrows(PdfRenderException::class.java) { FlowLayouter.paragraph(p, 40f, measurer) }
        assertEquals(PdfFailureCode.UNSUPPORTED_TEXT, error.code)
        assertTrue(error.message.orEmpty().contains("Mixed bidi"))
    }

    @Test
    fun `table fragmentation pieces retain each physical line height and offset`() {
        val table = PdfTable(listOf(40f), listOf(PdfTableRow(listOf(PdfTableCell(listOf(mixed()))))),
            cellMargins = PdfMargins(0f, 0f, 0f, 0f), border = null)
        val cell = TableLayouter.measure(table, measurer, table.columnWidths.sum()).cells.single()
        assertEquals(40f, cell.contentHeight)
        assertEquals(listOf(10f, 20f, 10f), cell.pieces.map { it.height })
        assertEquals(listOf(0f, 10f, 30f), cell.pieces.map { it.top })
    }

    @Test
    fun `paragraph border fragments follow the actual line heights`() {
        val edge = PdfParagraphEdge(PdfLineStroke(1f, PdfColor(0, 139, 144)), 4f)
        val p = mixed().copy(borders = PdfParagraphBorders(edge, edge, edge, edge))
        val measured = FlowLayouter.paragraph(p, 40f, measurer)
        assertEquals(listOf(15f, 20f, 15f), measured.heights)
        assertEquals(50f, measured.height())
        for ((index, expected) in measured.heights.withIndex()) {
            val sides = measured.lines[index].filterIsInstance<PositionedStroke>().filter { it.x == it.endX }
            assertEquals(2, sides.size)
            assertTrue(sides.all { it.endY - it.top == expected })
        }
    }

    @Test
    fun `variable heights beside floating images remain an explicit failure before and after wrapping`() {
        val pixels = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
        val image = PdfImage(ByteArrayOutputStream().also { ImageIO.write(pixels, "png", it) }.toByteArray(), 30f, 80f)
        for (width in listOf(40f, 120f)) {
            val p = mixed().copy(anchoredImages = listOf(PdfAnchoredImage(image, 20f, 0f, wrap = PdfImageWrap.RIGHT)))
            val page = PdfPage(width + 40f, 200f, PdfMargins(20f, 20f, 20f, 20f))
            val error = assertThrows(PdfRenderException::class.java) {
                FlowLayouter.layout(PdfDocument(page, listOf(p), PdfFlow()), measurer)
            }
            assertEquals(PdfFailureCode.UNSUPPORTED_TEXT, error.code)
            assertTrue(error.message.orEmpty().contains("Variable natural line boxes"))
        }
    }

    @Test
    fun `mixed real fonts render in body tables frames page bands and notes`() {
        val style = PdfTextStyle("regular", 10f)

        fun narrow(prefix: String, available: Float): PdfParagraph {
            val p = mixed(prefix)
            return p.copy(format = p.format.copy(rightIndent = available - 34f))
        }

        val header = PdfPageBand(5f, 55f, listOf(narrow("H", 340f)))
        val note = PdfNote("foot", PdfNoteKind.ENDNOTE, PdfListValue("notes", 0, 1, "1"),
            listOf(PdfParagraph(listOf(PdfNoteLabel(style)), 0f, 0f,
                PdfParagraphFormat(automaticLineSpacing = PdfAutomaticLineSpacing())), narrow("N", 340f)), PdfNoteSeparator(width = 100f))
        val table = PdfTable(listOf(100f), listOf(PdfTableRow(listOf(PdfTableCell(listOf(narrow("T", 100f)))))),
            cellMargins = PdfMargins(0f, 0f, 0f, 0f))
        val frame = PdfTextBox(200f, 250f, 110f, 90f, listOf(narrow("F", 110f)), PdfBoxAnchor.PAGE,
            padding = PdfMargins(0f, 0f, 0f, 0f))
        val call = PdfParagraph(listOf(PdfTextRun("Call", style), PdfNoteCall("call", "foot", style)),
            0f, 0f, PdfParagraphFormat(exactLineHeight = 14f))
        val doc = PdfDocument(PdfPage(400f, 600f, PdfMargins(100f, 30f, 100f, 30f)),
            listOf(call, narrow("B", 340f), table, frame), PdfFlow(header = header), notes = listOf(note))
        Loader.loadPDF(PdfEngine().render(doc, FoundationLetter.fonts())).use { pdf ->
            assertEquals(1, pdf.numberOfPages)
            val baselines = mutableMapOf<String, MutableList<Float>>()
            val text = object : PDFTextStripper() {

                override fun processTextPosition(position: TextPosition) {
                    if (position.unicode in setOf("H", "B", "T", "F", "N"))
                        baselines.getOrPut(position.unicode) { mutableListOf() } += position.yDirAdj
                    super.processTextPosition(position)
                }

            }.getText(pdf)
            for (prefix in listOf("H", "B", "T", "F", "N")) {
                for (number in 1..3) assertEquals(1, Regex("$prefix$number").findAll(text).count(), text)
                val y = baselines.getValue(prefix)
                assertEquals(3, y.size, prefix)
                assertEquals(20.879f, y[1] - y[0], .03f, "$prefix small-to-large baseline advance")
                assertEquals(13.618f, y[2] - y[1], .03f, "$prefix large-to-small baseline advance")
            }
        }
    }

}
