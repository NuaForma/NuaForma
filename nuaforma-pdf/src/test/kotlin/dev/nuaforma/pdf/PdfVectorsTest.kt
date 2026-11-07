package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PdfVectorsTest {
    private val vector = PdfVector(listOf(PdfVectorPath(listOf(PdfVectorPoint(100, 500), PdfVectorPoint(400, 800), PdfVectorPoint(900, 100)))))
    private val run = PdfVectorRun(vector, 10f, 10f, PdfColor(0, 120, 60))
    private val page = PdfPage(100f, 100f, PdfMargins(10f, 10f, 10f, 10f))

    @Test
    fun `standalone vectors honor paragraph mark metrics without adding descent to a taller object`() {
        val style = PdfTextStyle("mark", 11f)
        val format = PdfParagraphFormat(automaticLineSpacing = PdfAutomaticLineSpacing())
        val p = PdfParagraph(listOf(run), 0f, 0f, format).withMinimumLineStyle(style)
        val measurer = TextMeasurer { _, _ -> TextMetrics(5f, 12f, 3f) }
        assertEquals(15f to 15f, PageLayouter.automaticBox(p, measurer))
        val tall = p.copy(runs = listOf(run.copy(width = 20f, height = 20f)))
        assertEquals(20f to 20f, PageLayouter.automaticBox(tall, measurer))
    }


    @Test
    fun `vector only PDF requires no fonts or image resources and paints colored strokes`() {
        val paragraph = PdfParagraph(listOf(run), 0f, 0f, PdfParagraphFormat(automaticLineSpacing = PdfAutomaticLineSpacing()))
        val bytes = PdfEngine().render(PdfDocument(page, listOf(paragraph)), emptyList())
        Loader.loadPDF(bytes).use { pdf ->
            assertFalse(pdf.getPage(0).resources.fontNames.iterator().hasNext())
            assertFalse(pdf.getPage(0).resources.xObjectNames.iterator().hasNext())
            val image = PDFRenderer(pdf).renderImageWithDPI(0, 144f)
            var colored = 0
            for (y in 0 until image.height) for (x in 0 until image.width) {
                val rgb = image.getRGB(x, y)
                if ((rgb shr 8 and 255) > (rgb shr 16 and 255) + 20) colored++
            }
            assertTrue(colored > 10)
        }
    }

    @Test
    fun `atomic vectors wrap align and keep baseline through translation and alternating margins`() {
        val paragraph = PdfParagraph(List(9) { run }, 0f, 0f,
            PdfParagraphFormat(alignment = PdfAlignment.RIGHT, exactLineHeight = 14f))
        val layout = PageLayouter.layout(PdfDocument(page, listOf(paragraph)), TextMeasurer { _, _ -> error("No text font needed") })
        val objects = layout.elements.filterIsInstance<PositionedVector>()
        assertEquals(9, objects.size)
        assertEquals(10f, objects.first().x)
        assertEquals(80f, objects.last().x)
        assertEquals(14f, objects.last().top - objects.first().top)
        val marked = ParagraphPageOffsets.mark(objects.first(), PdfParagraphPageOffset(5f, PdfPageSide.EVEN))
        val translated = FlowLayouter.translate(marked, 20f, 30f)
        val resolved = ParagraphPageOffsets.resolve(PageLayout(page, listOf(translated)), 2).elements.single() as PositionedVector
        assertEquals(objects.first().x + 25f, resolved.x)
        assertEquals(objects.first().top + 30f, resolved.top)
    }

    @Test
    fun `cumulative vector geometry and physical size are bounded before publication`() {
        assertThrows(IllegalArgumentException::class.java) { PdfVectorRun(vector, Float.NaN, 10f, run.color) }
        assertThrows(IllegalArgumentException::class.java) { PdfVectorRun(vector, 20f, 10f, run.color) }
        val paragraph = PdfParagraph(List(1000) { run })
        val error = assertThrows(PdfRenderException::class.java) {
            PdfEngine().render(PdfDocument(page, List(7) { paragraph }), emptyList())
        }
        assertEquals(PdfFailureCode.RESOURCE_LIMIT, error.code)
    }
}
