package dev.nuaforma.pdf.adapters.pdfbox

import dev.nuaforma.pdf.*
import dev.nuaforma.pdf.internal.PageLayouter
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2
import org.apache.pdfbox.pdmodel.font.PDType0Font
import java.awt.Font
import java.awt.FontFormatException
import java.awt.font.FontRenderContext
import java.awt.font.TextAttribute
import java.awt.geom.AffineTransform
import java.io.IOException

/** Caller-font-only shaping. Faces, bounded caches, subset glyphs and Unicode maps belong to one render. */
internal class FontShaper(resources: List<PdfFont>, private val fonts: Map<String, PDType0Font>, private val control: PdfRenderControl) {
    data class Glyph(val id: Int, val x: Float, val y: Float, val logical: String)
    data class Shape(val glyphs: List<Glyph>, val advance: Float)
    private data class Key(val text: String, val font: String, val size: Float, val features: PdfFontFeatures, val characterSpacing: Float)
    private val supplied = resources.associateBy { it.id }
    private val faces = mutableMapOf<String, Font>()
    private val cache = linkedMapOf<Key, Shape>()
    private var cachedCharacters = 0
    private val enabledFonts = mutableSetOf<String>()
    private val renderContext = FontRenderContext(AffineTransform(), true, true)

    fun enabled(fontId: String): Boolean = fontId in enabledFonts

    fun shape(text: String, style: PdfTextStyle): Shape {
        control.checkActive()
        PageLayouter.requireSupportedText(text)
        if (text.isEmpty()) {
            font(style.fontId)
            return Shape(emptyList(), 0f)
        }
        val key = Key(text, style.fontId, style.size, style.fontFeatures, style.characterSpacing)
        return cache[key] ?: try {
            val natural = if (style.fontFeatures.kerning == PdfKerning.OPENTYPE || style.fontFeatures.standardLigatures)
                openType(text, style) else pairs(text, style)
            val result = if (style.characterSpacing == 0f) natural else tracked(natural, style.characterSpacing)
            val glyphCount = trueType(style.fontId).numberOfGlyphs
            if (!result.advance.isFinite() || result.advance < 0 || glyphCount !in 1..65535 ||
                result.glyphs.any { !it.x.isFinite() || !it.y.isFinite() || it.id !in 1 until glyphCount })
                fail(PdfFailureCode.INVALID_RESOURCE, "Invalid shaped font metrics or missing glyph")
            if (style.fontFeatures.active) enabledFonts += style.fontId
            remember(key, result)
            result
        } catch (_: IOException) { fail(PdfFailureCode.INVALID_RESOURCE, "Cannot read supplied font layout data") }
        catch (_: FontFormatException) { fail(PdfFailureCode.INVALID_RESOURCE, "Cannot shape the supplied TrueType font") }
    }

    private fun tracked(shape: Shape, spacing: Float): Shape {
        if (shape.glyphs.any { it.logical.codePointCount(0, it.logical.length) != 1 })
            fail(PdfFailureCode.UNSUPPORTED_TEXT, "Character spacing with complex or ligature clusters is not qualified")
        val glyphs = shape.glyphs.mapIndexed { index, glyph -> glyph.copy(x = glyph.x + index * spacing) }
        val advance = shape.advance + glyphs.size * spacing
        if (glyphs.zipWithNext().any { (before, after) -> after.x < before.x } || (glyphs.lastOrNull()?.x ?: 0f) > advance)
            fail(PdfFailureCode.UNSUPPORTED_TEXT, "Character spacing reverses glyph advances")
        return Shape(glyphs, advance)
    }

    private fun remember(key: Key, shape: Shape) {
        while (cache.isNotEmpty() && (cache.size >= 2048 || cachedCharacters + key.text.length > 100_000)) {
            val first = cache.keys.first()
            cachedCharacters -= first.text.length
            cache.remove(first)
        }
        if (key.text.length <= 100_000) {
            cache[key] = shape
            cachedCharacters += key.text.length
        }
    }

    private fun font(id: String) = fonts[id] ?: fail(PdfFailureCode.FONT_UNAVAILABLE, "Referenced font is not supplied")

    private fun trueType(id: String) = (font(id).descendantFont as PDCIDFontType2).trueTypeFont

    private fun glyphId(font: PDType0Font, text: String): Int {
        val code = try { font.encode(text) } catch (_: IllegalArgumentException) {
            fail(PdfFailureCode.MISSING_GLYPH, "The supplied font cannot encode requested text")
        }
        if (code.size != 2) fail(PdfFailureCode.INVALID_RESOURCE, "Unsupported shaped font encoding")
        return ((code[0].toInt() and 255) shl 8) or (code[1].toInt() and 255)
    }

    private fun pairs(text: String, style: PdfTextStyle): Shape {
        val font = font(style.fontId)
        val ttf = trueType(style.fontId)
        val scale = style.size / ttf.unitsPerEm
        val kern = if (style.fontFeatures.kerning == PdfKerning.PAIRS) ttf.kerning?.horizontalKerningSubtable else null
        var x = 0f
        var previous: Int? = null
        val glyphs = text.codePoints().toArray().map { point ->
            control.checkActive()
            val logical = String(Character.toChars(point))
            val gid = glyphId(font, logical)
            previous?.let { x += (kern?.getKerning(it, gid) ?: 0) * scale }
            Glyph(gid, x, 0f, logical).also { x += ttf.getAdvanceWidth(gid) * scale; previous = gid }
        }
        return Shape(glyphs, x)
    }

    private fun openType(text: String, style: PdfTextStyle): Shape {
        val base = faces.getOrPut(style.fontId) {
            val resource = supplied[style.fontId] ?: fail(PdfFailureCode.FONT_UNAVAILABLE, "Referenced font is not supplied")
            resource.bytes().inputStream().use { Font.createFont(Font.TRUETYPE_FONT, it) }
        }
        val face = base.deriveFont(mapOf(TextAttribute.SIZE to style.size,
            TextAttribute.KERNING to if (style.fontFeatures.kerning == PdfKerning.OPENTYPE) TextAttribute.KERNING_ON else 0,
            TextAttribute.LIGATURES to if (style.fontFeatures.standardLigatures) TextAttribute.LIGATURES_ON else 0))
        val chars = text.toCharArray()
        val vector = face.layoutGlyphVector(renderContext, chars, 0, chars.size, Font.LAYOUT_LEFT_TO_RIGHT)
        val indices = vector.getGlyphCharIndices(0, vector.numGlyphs, null)
        if (indices.distinct().size != indices.size || indices.firstOrNull() != 0 || indices.any { it !in chars.indices } ||
            !indices.toList().zipWithNext().all { (a, b) -> a < b })
            fail(PdfFailureCode.UNSUPPORTED_TEXT, "Font features require unsupported glyph reordering or complex clusters")
        val ttf = trueType(style.fontId)
        val kern = if (style.fontFeatures.kerning == PdfKerning.PAIRS) ttf.kerning?.horizontalKerningSubtable else null
        var adjustment = 0f
        var previous: Int? = null
        val glyphs = indices.mapIndexed { index, start ->
            control.checkActive()
            val gid = vector.getGlyphCode(index)
            if (gid == face.missingGlyphCode) fail(PdfFailureCode.MISSING_GLYPH, "The supplied font cannot shape requested text")
            previous?.let { adjustment += (kern?.getKerning(it, gid) ?: 0) * style.size / ttf.unitsPerEm }
            previous = gid
            val position = vector.getGlyphPosition(index)
            Glyph(gid, position.x.toFloat() + adjustment, position.y.toFloat(), text.substring(start, indices.getOrNull(index + 1) ?: text.length))
        }
        return Shape(glyphs, vector.getGlyphPosition(vector.numGlyphs).x.toFloat() + adjustment)
    }

    private fun fail(code: PdfFailureCode, message: String): Nothing = throw PdfRenderException(code, message)

}
