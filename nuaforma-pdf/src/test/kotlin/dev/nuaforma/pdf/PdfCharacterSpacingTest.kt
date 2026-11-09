package dev.nuaforma.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PdfCharacterSpacingTest {

    private fun model(spacing: Float, features: PdfFontFeatures = PdfFontFeatures()): PdfDocument {
        val plain = PdfTextStyle("regular", 16f).withFontFeatures(features)
        val tagged = plain.copy(color = PdfColor(28, 126, 130))
        val tracked = tagged.withCharacterSpacing(spacing)
        val format = PdfParagraphFormat(exactLineHeight = 30f)
        return PdfDocument(PdfPage(595.28f, 841.89f, PdfMargins(36f, 36f, 36f, 36f)), listOf(
            PdfParagraph(listOf(PdfTextRun("ABCDEF", tracked), PdfTextRun("TAIL", plain)), 0f, 0f, format),
            PdfParagraph(listOf(PdfTextRun("ABCDEF", tagged), PdfTextRun("TAIL", plain)), 0f, 0f, format)))
    }

    private fun render(engine: PdfEngine, spacing: Float, features: PdfFontFeatures = PdfFontFeatures()) =
        engine.render(model(spacing, features), listOf(PdfFont("regular", FoundationLetter.resource("/fonts/NotoSerif-Light.ttf"))))

    private fun positions(bytes: ByteArray): List<Triple<String, Float, Float>> {
        val result = mutableListOf<Triple<String, Float, Float>>()
        Loader.loadPDF(bytes).use { pdf ->
            object : PDFTextStripper() {

                override fun processTextPosition(position: TextPosition) {
                    result += Triple(position.unicode, position.xDirAdj, position.yDirAdj)
                    super.processTextPosition(position)
                }

            }.getText(pdf)
            assertTrue(pdf.getPage(0).resources.fontNames.all { pdf.getPage(0).resources.getFont(it).isEmbedded })
        }
        return result
    }

    @Test
    fun `additional advances include the last character and do not leak into following text or cached shapes`() {
        val engine = PdfEngine()
        for (kerning in PdfKerning.entries) {
            val features = PdfFontFeatures(kerning)
            val baseline = positions(render(engine, 0f, features))
            assertEquals(20, baseline.size)
            for (spacing in listOf(.75f, -.5f)) {
                val actual = positions(render(engine, spacing, features))
                assertEquals(baseline.map { it.first }, actual.map { it.first })
                for (index in actual.indices) {
                    val expectedShift = if (index >= 10) 0f else minOf(index, 6) * spacing
                    assertEquals(baseline[index].second + expectedShift, actual[index].second, .002f, "$kerning $spacing character $index")
                    assertEquals(baseline[index].third, actual[index].third, .002f)
                }
            }
        }
    }

    @Test
    fun `copies and decorations preserve tracking and explicit zero retains the original style`() {
        val original = PdfTextStyle("regular", 16f)
        val tracked = original.withCharacterSpacing(.75f)
        assertEquals(.75f, tracked.copy(size = 18f).characterSpacing)
        assertEquals(.75f, tracked.withStrikethrough(true).withSyntheticBold(true)
            .withFontFeatures(PdfFontFeatures(PdfKerning.PAIRS)).characterSpacing)
        assertNotEquals(original, tracked)
        assertEquals(original, tracked.withCharacterSpacing(-0f))
        assertEquals(original.hashCode(), tracked.withCharacterSpacing(0f).hashCode())
        assertEquals(original.toString(), original.withCharacterSpacing(0f).toString())
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 14_401f, -14_401f)) {
            assertThrows(IllegalArgumentException::class.java) { original.withCharacterSpacing(invalid) }
        }
    }

    @Test
    fun `reversed advances ligature clusters and RTL tracking remain explicit failures`() {
        val engine = PdfEngine()
        assertEquals(PdfFailureCode.UNSUPPORTED_TEXT, assertThrows(PdfRenderException::class.java) { render(engine, -100f) }.code)
        val style = PdfTextStyle("regular", 16f).withFontFeatures(PdfFontFeatures(PdfKerning.OPENTYPE, true)).withCharacterSpacing(1f)
        val fonts = listOf(PdfFont("regular", FoundationLetter.resource("/fonts/NotoSerif-Light.ttf")))
        val page = PdfPage(595.28f, 841.89f, PdfMargins(36f, 36f, 36f, 36f))
        val ligature = PdfDocument(page, listOf(PdfParagraph(listOf(PdfTextRun("office", style)), 0f, 0f,
            PdfParagraphFormat(exactLineHeight = 30f))))
        assertEquals(PdfFailureCode.UNSUPPORTED_TEXT, assertThrows(PdfRenderException::class.java) { engine.render(ligature, fonts) }.code)
        val bidi = PdfDocument(page, listOf(PdfParagraph(listOf(PdfTextRun("ABC", style)), 0f, 0f,
            PdfParagraphFormat(exactLineHeight = 30f, textDirection = PdfTextDirection.RIGHT_TO_LEFT))))
        assertEquals(PdfFailureCode.UNSUPPORTED_TEXT, assertThrows(PdfRenderException::class.java) { engine.render(bidi, fonts) }.code)
    }

    @Test
    fun `explicit left to right tracking retains ordinary glyph positions`() {
        val engine = PdfEngine()
        val implicit = model(.75f)
        val explicit = PdfDocument(implicit.page, implicit.blocks.map { block ->
            val paragraph = block as PdfParagraph
            paragraph.copy(format = paragraph.format.copy(textDirection = PdfTextDirection.LEFT_TO_RIGHT))
        })
        val fonts = listOf(PdfFont("regular", FoundationLetter.resource("/fonts/NotoSerif-Light.ttf")))
        assertEquals(positions(engine.render(implicit, fonts)), positions(engine.render(explicit, fonts)))
    }

    @Test
    fun `concurrent documents retain independent character spacing`() {
        val engine = PdfEngine()
        val baseline = positions(render(engine, 0f))
        val workers = Executors.newFixedThreadPool(2)
        try {
            val tasks = (0..5).map { index -> workers.submit<Boolean> {
                val spacing = if (index % 2 == 0) .75f else -.5f
                val actual = positions(render(engine, spacing))
                kotlin.math.abs(actual[6].second - baseline[6].second - 6 * spacing) < .002f &&
                    actual.drop(10) == baseline.drop(10)
            } }
            assertTrue(tasks.all { it.get(30, TimeUnit.SECONDS) })
        } finally { workers.shutdownNow() }
    }

}
