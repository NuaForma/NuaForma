package dev.nuaforma.pdf.adapters.pdfbox

import dev.nuaforma.pdf.*
import dev.nuaforma.pdf.internal.PageLayouter
import dev.nuaforma.pdf.internal.BidiLine
import dev.nuaforma.pdf.internal.LogicalTextSlice
import java.text.Bidi
import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.cos.COSFloat
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSString
import org.apache.pdfbox.pdfwriter.ContentStreamWriter
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import kotlin.math.cos
import kotlin.math.sin

internal class PdfWatermarkPainter(private val watermark: PdfWatermark, private val font: PDType0Font, private val width: Float) {
    private val visualText: String
    private val textWidth: Float
    private val textHeight: Float
    private val textCenter: Float

    init {
        val points = watermark.text.codePoints().toArray()
        val hebrew = points.any { Character.UnicodeScript.of(it) == Character.UnicodeScript.HEBREW }
        if (hebrew && points.any { Character.getDirectionality(it) == Character.DIRECTIONALITY_LEFT_TO_RIGHT })
            throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Mixed Hebrew and LTR watermark text is not qualified")
        PageLayouter.requireSupportedText(watermark.text, hebrew)
        visualText = if (hebrew) BidiLine.visualRuns(Bidi(watermark.text, Bidi.DIRECTION_RIGHT_TO_LEFT),
            listOf(LogicalTextSlice(watermark.text, PdfTextStyle(watermark.fontId, 1f), 0))).joinToString("") { it.text } else watermark.text
        textWidth = try { font.getStringWidth(visualText) / 1000f }
        catch (_: IllegalArgumentException) { throw PdfRenderException(PdfFailureCode.MISSING_GLYPH, "The supplied watermark font cannot encode the text") }
        textHeight = (font.fontDescriptor.ascent - font.fontDescriptor.descent) / 1000f
        textCenter = (font.fontDescriptor.ascent + font.fontDescriptor.descent) / 2000f
        if (!textWidth.isFinite() || textWidth <= 0 || !textHeight.isFinite() || textHeight <= 0)
            throw PdfRenderException(PdfFailureCode.INVALID_RESOURCE, "Invalid watermark font metrics")
        if (font.willBeSubset()) visualText.codePoints().forEach { font.addToSubset(it) }
    }

    fun paint(page: PDPage, writer: ContentStreamWriter, pageWidth: Float, pageHeight: Float) {
        fun command(name: String, vararg operands: Any) = writer.writeTokens(operands.toList() + Operator.getOperator(name))
        fun number(value: Float) = COSFloat(value)
        val angle = -Math.toRadians(watermark.rotation.toDouble())
        val c = cos(angle).toFloat()
        val s = sin(angle).toFloat()
        val fontSize = width / 4 / textHeight
        val scaleX = width / (textWidth * fontSize)
        command("q")
        if (watermark.opacity < 1f) {
            val state = PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = watermark.opacity }
            command("gs", page.resources.add(state))
        }
        val logical = COSDictionary().apply { setString(COSName.ACTUAL_TEXT, watermark.text) }
        command("BDC", COSName.ARTIFACT, logical)
        command("BT")
        command("Tf", page.resources.add(font), number(fontSize))
        command("rg", number(watermark.color.red / 255f), number(watermark.color.green / 255f), number(watermark.color.blue / 255f))
        command("Tm", number(scaleX * c), number(scaleX * s), number(-s), number(c),
            number(pageWidth / 2 - width / 2 * c + textCenter * fontSize * s),
            number(pageHeight / 2 - width / 2 * s - textCenter * fontSize * c))
        command("Tj", COSString(font.encode(visualText)))
        command("ET")
        command("EMC")
        command("Q")
    }
}
