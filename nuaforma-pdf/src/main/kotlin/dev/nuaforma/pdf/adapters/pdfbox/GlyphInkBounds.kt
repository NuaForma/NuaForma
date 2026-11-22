package dev.nuaforma.pdf.adapters.pdfbox

import dev.nuaforma.pdf.*
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2
import org.apache.pdfbox.pdmodel.font.PDType0Font
import java.awt.BasicStroke
import java.awt.geom.AffineTransform

/** Physical ink uses the placed glyph outlines rather than unrelated extrema of the whole face. */
internal object GlyphInkBounds {

    fun measure(font: PDType0Font, shape: FontShaper.Shape, style: PdfTextStyle,
                lineMetrics: FloatArray?, control: PdfRenderControl): Pair<Float, Float> {
        val face = (font.descendantFont as PDCIDFontType2).trueTypeFont
        val outlines = face.glyph ?: throw PdfRenderException(PdfFailureCode.INVALID_RESOURCE, "Cannot read supplied font layout data")
        val scale = style.size.toDouble() / face.unitsPerEm
        val transform = AffineTransform.getScaleInstance(scale, scale)
        var ascent = 0f
        var descent = 0f
        for (glyph in shape.glyphs) {
            control.checkActive()
            val outline = outlines.getGlyph(glyph.id)?.path ?: continue
            if (outline.bounds2D.isEmpty) continue
            val path = transform.createTransformedShape(outline)
            val bounds = if (style.syntheticBold) BasicStroke(style.size / 35f).createStrokedShape(path).bounds2D else path.bounds2D
            ascent = maxOf(ascent, bounds.maxY.toFloat() + style.baselineShift - glyph.y)
            descent = maxOf(descent, -bounds.minY.toFloat() - style.baselineShift + glyph.y)
        }

        fun decoration(positionIndex: Int, thicknessIndex: Int) {
            val kind = if (positionIndex == 3) "underline" else "strikeout"
            val position = lineMetrics?.getOrNull(positionIndex)?.takeIf { it.isFinite() }
                ?: throw PdfRenderException(PdfFailureCode.INVALID_RESOURCE, "Font has no qualified $kind metrics")
            val thickness = lineMetrics.getOrNull(thicknessIndex)?.takeIf { it.isFinite() }
                ?: throw PdfRenderException(PdfFailureCode.INVALID_RESOURCE, "Font has no qualified $kind thickness")
            val underline = if (positionIndex == 3 && style.thickUnderline) underlineGeometry(style, position, thickness) else null
            val top = underline?.top ?: position * style.size
            val depth = underline?.let { -it.bottom } ?: (thickness - position) * style.size
            ascent = maxOf(ascent, top + style.baselineShift)
            descent = maxOf(descent, depth - style.baselineShift)
        }

        if (style.underline) decoration(3, 4)
        if (style.strikethrough) decoration(5, 6)
        return ascent to descent
    }
}

/** Physical underline geometry in points relative to the baseline. */
internal data class UnderlineGeometry(val top: Float, val thickness: Float, val bottom: Float)

internal fun underlineGeometry(style: PdfTextStyle, position: Float, thickness: Float): UnderlineGeometry {
    val top = position * style.size
    val weight = thickness * style.size
    if (!style.thickUnderline) return UnderlineGeometry(top, weight, (position - thickness) * style.size)
    val thickWeight = weight / 2f + 1f
    return UnderlineGeometry(top / 2f, thickWeight, top / 2f - thickWeight)
}
