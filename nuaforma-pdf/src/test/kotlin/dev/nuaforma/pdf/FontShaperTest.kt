package dev.nuaforma.pdf

import dev.nuaforma.pdf.adapters.pdfbox.FontShaper
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FontShaperTest {

    private fun style(features: PdfFontFeatures) = PdfTextStyle("regular", 20f).withFontFeatures(features)

    private fun withShaper(file: String = "NotoSerif-Regular.ttf", block: (FontShaper) -> Unit) {
        val bytes = FoundationLetter.resource("/fonts/$file")
        PDDocument().use { pdf ->
            val font = PDType0Font.load(pdf, bytes.inputStream(), true)
            block(FontShaper(listOf(PdfFont("regular", bytes)), mapOf("regular" to font), PdfRenderControl()))
        }
    }

    @Test fun `Noto OpenType kerning and standard ligatures use the retained font's real glyphs and advances`() {
        withShaper { shaper ->
            val plain = shaper.shape("AVATAR To WA", style(PdfFontFeatures()))
            val kerned = shaper.shape("AVATAR To WA", style(PdfFontFeatures(PdfKerning.OPENTYPE)))
            assertEquals(150.42f, plain.advance, .002f)
            assertEquals(142.42f, kerned.advance, .002f)
            assertEquals(12, kerned.glyphs.size)
            val original = shaper.shape("office", style(PdfFontFeatures()))
            val ligature = shaper.shape("office", style(PdfFontFeatures(standardLigatures = true)))
            assertEquals(6, original.glyphs.size)
            assertEquals(listOf("o", "ffi", "c", "e"), ligature.glyphs.map { it.logical })
            assertTrue(ligature.advance < original.advance)
            assertTrue(shaper.enabled("regular"))
            assertFalse(shaper.enabled("other"))
            val empty = shaper.shape("", style(PdfFontFeatures(PdfKerning.OPENTYPE, true)))
            assertEquals(0f, empty.advance)
            assertTrue(empty.glyphs.isEmpty())
        }
    }

    @Test fun `legacy pair kerning and fonts without a kern table remain distinct from OpenType positioning`() {
        withShaper("LiberationSans-Regular.ttf") { shaper ->
            val plain = shaper.shape("AVATAR To WA", style(PdfFontFeatures()))
            val pairs = shaper.shape("AVATAR To WA", style(PdfFontFeatures(PdfKerning.PAIRS)))
            assertEquals(146.68945f, plain.advance, .002f)
            assertEquals(137.43164f, pairs.advance, .002f)
        }
        withShaper { shaper ->
            assertEquals(shaper.shape("AVATAR", style(PdfFontFeatures())).advance,
                shaper.shape("AVATAR", style(PdfFontFeatures(PdfKerning.PAIRS))).advance)
            val mixed = shaper.shape("AVATAR office", style(PdfFontFeatures(PdfKerning.PAIRS, true)))
            assertTrue(mixed.glyphs.any { it.logical == "ffi" })
        }
    }

    @Test fun `missing supplied faces missing glyphs and unqualified shaping scripts fail explicitly`() {
        withShaper { shaper ->
            val active = style(PdfFontFeatures(PdfKerning.OPENTYPE, true))
            assertEquals(PdfFailureCode.FONT_UNAVAILABLE, assertThrows(PdfRenderException::class.java) { shaper.shape("Text", active.copy(fontId = "missing")) }.code)
            assertEquals(PdfFailureCode.MISSING_GLYPH, assertThrows(PdfRenderException::class.java) { shaper.shape("🦄", active) }.code)
            for (text in listOf("שלום", "a\u0301")) assertEquals(PdfFailureCode.UNSUPPORTED_TEXT,
                assertThrows(PdfRenderException::class.java) { shaper.shape(text, active) }.code)
        }
    }

    @Test fun `old style constructors copies equality and decorations retain additive feature settings`() {
        val original = PdfTextStyle("regular", 20f, languageTag = "nl-NL", underline = true).withStrikethrough(true)
        assertEquals(PdfFontFeatures(), original.fontFeatures)
        val features = PdfFontFeatures(PdfKerning.OPENTYPE, true)
        val shaped = original.withFontFeatures(features)
        assertEquals(features, shaped.copy(size = 18f).fontFeatures)
        assertEquals(features, shaped.withStrikethrough(false).fontFeatures)
        assertTrue(shaped.underline && shaped.strikethrough)
        assertEquals("nl-NL", shaped.languageTag)
        assertNotEquals(original, shaped)
        assertEquals(shaped, shaped.copy())
        assertEquals(shaped.hashCode(), shaped.copy().hashCode())
        assertTrue(shaped.toString().contains("fontFeatures="))
        assertFalse(original.toString().contains("fontFeatures="))
    }

}
