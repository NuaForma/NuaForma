package dev.nuaforma.pdf

import org.apache.fontbox.ttf.TTFParser
import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.RandomAccessReadBuffer
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PdfPrivateGlyphTest {
    private val privateText = "\uF003\uF004\uF00C\uF00D"
    private val aliases = "\u2709\u2665\u2713\u00D7"

    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream("/fonts/$name")).use { it.readBytes() }

    private fun missingPrivateGlyph(): String = TTFParser().parse(RandomAccessReadBuffer(resource("FontAwesomeFree-Solid.ttf"))).use { font ->
        val lookup = font.unicodeCmapLookup
        (0xE000..0xF8FF).first { lookup.getGlyphId(it) == 0 }.toChar().toString()
    }

    private fun fonts(embedding: PdfFontEmbedding = PdfFontEmbedding.SUBSET) = listOf(
        PdfFont("symbols", resource("FontAwesomeFree-Solid.ttf"), embedding), PdfFont("regular", resource("LiberationSans-Regular.ttf")))

    private fun document(text: String) = PdfDocument(PdfPage(300f, 200f, PdfMargins(20f, 20f, 20f, 20f)),
        listOf(PdfParagraph(listOf(PdfTextRun("Before ", PdfTextStyle("regular", 11f)), PdfTextRun(text, PdfTextStyle("symbols", 16f)),
            PdfTextRun(" after", PdfTextStyle("regular", 11f))), format = PdfParagraphFormat(exactLineHeight = 24f))))

    @Test
    fun `private use characters and assigned aliases have identical physical glyphs without modifying the font`() {
        TTFParser().parse(RandomAccessReadBuffer(resource("FontAwesomeFree-Solid.ttf"))).use { font ->
            val lookup = font.unicodeCmapLookup
            for ((private, assigned) in privateText.zip(aliases)) {
                val glyph = lookup.getGlyphId(private.code)
                assertTrue(glyph > 0)
                assertEquals(glyph, lookup.getGlyphId(assigned.code))
            }
        }
    }

    @Test
    fun `subset and full embedding preserve original private text and match assigned alias pixels`() {
        val engine = PdfEngine()
        for (embedding in PdfFontEmbedding.entries) {
            val fonts = fonts(embedding)
            Loader.loadPDF(engine.render(document(aliases), fonts)).use { control ->
                Loader.loadPDF(engine.render(document(privateText), fonts)).use { actual ->
                    assertEquals("Before $privateText after", PDFTextStripper().getText(actual).trim())
                    assertEquals(1, actual.numberOfPages)
                    assertTrue(actual.getPage(0).resources.fontNames.all { actual.getPage(0).resources.getFont(it).isEmbedded })
                    assertImagesMatch(PDFRenderer(control).renderImageWithDPI(0, 96f), PDFRenderer(actual).renderImageWithDPI(0, 96f)) {
                        pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                    }
                }
            }
        }
    }

    @Test
    fun `missing private glyphs do not activate fallback and unsupported controls scripts and supplementary private use still reject`() {
        val engine = PdfEngine()
        assertEquals(PdfFailureCode.MISSING_GLYPH, assertThrows(PdfRenderException::class.java) { engine.render(document(missingPrivateGlyph()), fonts()) }.code)
        for (text in listOf("\u200D", "\u0378", "\u0301", String(Character.toChars(0xF0000)), String(Character.toChars(0x100000)))) {
            assertEquals(PdfFailureCode.UNSUPPORTED_TEXT, assertThrows(PdfRenderException::class.java) { engine.render(document(text), fonts()) }.code)
        }
    }

    @Test
    fun `assigned and private aliases coexist in either order without losing their logical characters`() {
        val engine = PdfEngine()
        for (embedding in PdfFontEmbedding.entries) for (text in listOf("$privateText$aliases", "$aliases$privateText")) {
            Loader.loadPDF(engine.render(document(text), fonts(embedding))).use { pdf ->
                assertEquals("Before $text after", PDFTextStripper().getText(pdf).trim())
            }
        }
    }

    @Test
    fun `watermark private glyphs do not overwrite assigned body aliases of the same font`() {
        for (embedding in PdfFontEmbedding.entries) {
            val bytes = PdfEngine().render(document(aliases), fonts(embedding), PdfOutputOptions(), PdfRenderControl(),
                PdfWatermark(privateText, "symbols", opacity = .3f, rotation = 0f))
            Loader.loadPDF(bytes).use { pdf ->
                val text = PDFTextStripper().getText(pdf)
                assertTrue(text.contains(privateText))
                assertTrue(text.contains("Before $aliases after"))
            }
        }
    }

    @Test
    fun `shared engine resolves private glyph subsets independently for concurrent callers`() {
        val engine = PdfEngine()
        val fonts = fonts()
        val pool = Executors.newFixedThreadPool(3)
        try {
            val jobs = privateText.map { glyph -> pool.submit<String> {
                Loader.loadPDF(engine.render(document(glyph.toString()), fonts)).use { PDFTextStripper().getText(it).trim() }
            } }
            assertEquals(privateText.map { "Before $it after" }, jobs.map { it.get(30, TimeUnit.SECONDS) })
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

}
