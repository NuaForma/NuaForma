package dev.nuaforma.pdf.adapters.pdfbox

import dev.nuaforma.pdf.*
import dev.nuaforma.pdf.internal.*
import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSFloat
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSString
import org.apache.pdfbox.pdfwriter.ContentStreamWriter
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.common.PDStream
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.apache.pdfbox.pdfwriter.compress.CompressParameters

internal object PdfBoxWriter {
    fun render(document: PdfDocument, suppliedFonts: List<PdfFont>, options: PdfOutputOptions, control: PdfRenderControl): ByteArray {
        control.checkActive()
        PDDocument().use { pdf ->
            pdf.version = if (options.profile == PdfOutputProfile.PDFA_1B) 1.4f else 1.7f
            ArchivalMetadata.apply(pdf, options)
            val navigation = PdfNavigationWriter(pdf)
            val lineMetrics = mutableMapOf<String, FloatArray>()
            val fonts = suppliedFonts.associate { resource ->
                control.checkActive()
                val bytes = resource.bytes()
                validateFontPermissions(bytes, resource.embedding)
                naturalMetrics(bytes)?.let { lineMetrics[resource.id] = it }
                val font = try {
                    PDType0Font.load(pdf, ByteArrayInputStream(bytes), resource.embedding == PdfFontEmbedding.SUBSET)
                } catch (_: IOException) {
                    fail(PdfFailureCode.INVALID_RESOURCE, "Cannot load the supplied TrueType font")
                }
                resource.id to font
            }
            val measurer = TextMeasurer { text, style ->
                control.checkActive()
                val font = fonts[style.fontId] ?: fail(PdfFailureCode.FONT_UNAVAILABLE, "Referenced font is not supplied")
                val width = try {
                    font.getStringWidth(text) * style.size / 1000f
                } catch (_: IllegalArgumentException) {
                    fail(PdfFailureCode.MISSING_GLYPH, "The supplied font cannot encode requested text")
                }
                val box = font.fontDescriptor.fontBoundingBox
                TextMetrics(width, maxOf(font.fontDescriptor.ascent, box.upperRightY) * style.size / 1000f + style.baselineShift,
                    maxOf(-font.fontDescriptor.descent, -box.lowerLeftY) * style.size / 1000f - style.baselineShift,
                    lineMetrics[style.fontId]?.get(0)?.times(style.size)?.plus(style.baselineShift) ?: Float.NaN,
                    lineMetrics[style.fontId]?.get(1)?.times(style.size)?.minus(style.baselineShift) ?: Float.NaN,
                    lineMetrics[style.fontId]?.get(2)?.times(style.size) ?: Float.NaN)
            }
            val composed = if (ReferenceLayouter.active(document)) ReferenceLayouter.layout(document, measurer, control)
                else DocumentLayouter.plain(document, measurer, control)
            DocumentLayouter.validatePlacedLinks(composed)
            val layouts = composed.pages
            fun decorationSize(elements: List<PositionedElement>): Long = elements.sumOf { element -> when (element) {
                is PositionedText -> if (element.decoration) element.text.length.toLong() else 0L
                is PositionedBox -> decorationSize(element.elements)
                else -> 0L
            } }
            if (layouts.sumOf { decorationSize(it.elements) } > 100_000) fail(PdfFailureCode.RESOURCE_LIMIT, "Generated leader budget exceeded")
            fun borderSize(elements: List<PositionedElement>): Int = elements.sumOf { when (it) { is PositionedTableBorder -> 1; is PositionedBox -> borderSize(it.elements); else -> 0 } }
            if (layouts.sumOf { borderSize(it.elements) } > 20_000) fail(PdfFailureCode.RESOURCE_LIMIT, "Table border budget exceeded")
            val usedFonts = linkedSetOf<String>()
            val images = java.util.IdentityHashMap<PdfImage, PDImageXObject>()
            var decodedImageBytes = 0L
            for (layout in layouts) {
                val page = PDPage(PDRectangle(layout.page.width, layout.page.height))
                page.resources = PDResources()
                pdf.addPage(page)
                val content = PDStream(pdf)
                page.setContents(content)
                content.createOutputStream(COSName.FLATE_DECODE).use { stream ->
                    val writer = ContentStreamWriter(stream)
                    fun command(name: String, vararg operands: Any) {
                        writer.writeTokens(operands.toList() + Operator.getOperator(name))
                    }
                    fun number(value: Float) = COSFloat(value)
                    fun paint(elements: List<PositionedElement>, canvasHeight: Float, canvasWidth: Float) {
                        for (element in elements.sortedBy { it is PositionedImage && it.foreground }) {
                            control.checkActive()
                            when (element) {
                                is PositionedBox -> {
                                    val angle = Math.toRadians(element.box.rotation.toDouble())
                                    val c = kotlin.math.cos(angle).toFloat()
                                    val s = kotlin.math.sin(angle).toFloat()
                                    val cx = element.box.width / 2
                                    val cy = element.box.height / 2
                                    command("q")
                                    command("cm", number(c), number(-s), number(s), number(c),
                                        number(element.x + cx - c * cx - s * cy),
                                        number(canvasHeight - element.top - cy + s * cx - c * cy))
                                    paint(element.elements, element.box.height, element.box.width)
                                    command("Q")
                                }
                                is PositionedRectangle -> {
                                    command("q")
                                    element.fill?.let { color ->
                                        command("rg", number(color.red / 255f), number(color.green / 255f), number(color.blue / 255f))
                                        command("re", number(element.x), number(canvasHeight - element.top - element.height), number(element.width), number(element.height))
                                        command("f")
                                    }
                                    element.border?.let { border ->
                                        command("RG", number(border.color.red / 255f), number(border.color.green / 255f), number(border.color.blue / 255f))
                                        command("w", number(border.width))
                                        command("re", number(element.x), number(canvasHeight - element.top - element.height), number(element.width), number(element.height))
                                        command("S")
                                    }
                                    command("Q")
                                }
                                is PositionedTableBorder -> Unit
                                is PositionedText -> {
                                    if (element.navigation != null) navigation.add(element, page, measurer.measure(element.text, element.style))
                                    if (element.decoration) command("BMC", COSName.ARTIFACT)
                                    element.style.languageTag?.let { language ->
                                        val properties = COSDictionary().apply { setString(COSName.LANG, language) }
                                        command("BDC", COSName.getPDFName("Span"), properties)
                                    }
                                    val color = element.style.color
                                    val font = fonts.getValue(element.style.fontId)
                                    usedFonts += element.style.fontId
                                    if (font.willBeSubset()) element.text.codePoints().forEach { font.addToSubset(it) }
                                    command("BT")
                                    command("Tf", page.resources.add(font), number(element.style.size))
                                    command("rg", number(color.red / 255f), number(color.green / 255f), number(color.blue / 255f))
                                    command("Tm", number(1f), number(0f), number(0f), number(1f), number(element.x), number(canvasHeight - (element.baseline - element.style.baselineShift)))
                                    // Write the same qualified cmap glyphs used for measurement, without a second GSUB pass.
                                    command("Tj", COSString(font.encode(element.text)))
                                    command("ET")
                                    if (element.style.underline) {
                                        val metrics = lineMetrics[element.style.fontId]
                                        val position = metrics?.getOrNull(3) ?: fail(PdfFailureCode.INVALID_RESOURCE, "Font has no qualified underline metrics")
                                        val thickness = metrics.getOrNull(4) ?: fail(PdfFailureCode.INVALID_RESOURCE, "Font has no qualified underline thickness")
                                        if (position - thickness < font.fontDescriptor.fontBoundingBox.lowerLeftY / 1000f)
                                            fail(PdfFailureCode.UNSUPPORTED_TEXT, "Underline exceeds the qualified font bounds")
                                        // OpenType post.underlinePosition denotes the top edge, unlike PostScript's center coordinate.
                                        command("re", number(element.x), number(canvasHeight - (element.baseline - element.style.baselineShift) + (position - thickness) * element.style.size),
                                            number(measurer.measure(element.text, element.style).width), number(thickness * element.style.size))
                                        command("f")
                                    }
                                    if (element.style.languageTag != null) command("EMC")
                                    if (element.decoration) command("EMC")
                                }
                                is PositionedImage -> {
                                    val image = images.getOrPut(element.image) {
                                        val bytes = element.image.bytes()
                                        validatePng(bytes)
                                        val dimensions = ByteBuffer.wrap(bytes, 16, 8).order(ByteOrder.BIG_ENDIAN)
                                        val bytesPerPixel = if ((bytes[24].toInt() and 255) > 8) 8 else 4
                                        val decoded = dimensions.int.toLong() * dimensions.int.toLong() * bytesPerPixel
                                        if (decoded > control.maxDecodedImageBytes - decodedImageBytes) fail(PdfFailureCode.RESOURCE_LIMIT, "Decoded image budget exceeded")
                                        decodedImageBytes += decoded
                                        if (options.profile == PdfOutputProfile.PDFA_1B && pngHasTransparency(bytes)) fail(PdfFailureCode.UNSUPPORTED_OUTPUT, "PDF/A-1b does not support transparent PNG resources")
                                        try { PDImageXObject.createFromByteArray(pdf, bytes, "image") }
                                        catch (_: IOException) { fail(PdfFailureCode.INVALID_RESOURCE, "Cannot decode the supplied PNG") }
                                    }
                                    command("q")
                                    val crop = element.image.crop
                                    val width = element.image.width / (1f - crop.left - crop.right)
                                    val height = element.image.height / (1f - crop.top - crop.bottom)
                                    if (crop != PdfImageCrop()) {
                                        command("re", number(element.x), number(canvasHeight - element.top - element.image.height), number(element.image.width), number(element.image.height))
                                        command("W"); command("n")
                                    }
                                    command("cm", number(width), number(0f), number(0f), number(height),
                                        number(element.x - width * crop.left), number(canvasHeight - element.top - height * (1f - crop.top)))
                                    command("Do", page.resources.add(image))
                                    command("Q")
                                }
                            }
                        }
                        val tableBorders = TableBorderLayouter.rectangles(elements, canvasWidth, canvasHeight)
                        if (tableBorders.isNotEmpty()) paint(tableBorders, canvasHeight, canvasWidth)
                    }
                    paint(layout.elements, layout.page.height, layout.page.width)
                }
            }
            navigation.finish()
            usedFonts.map { fonts.getValue(it) }.filter { it.willBeSubset() }.forEach { it.subset() }
            val output = BoundedOutput(control)
            if (options.profile == PdfOutputProfile.PDFA_1B) pdf.save(output, CompressParameters.NO_COMPRESSION) else pdf.save(output)
            control.checkActive()
            return output.toByteArray()
        }
    }

    private fun validateFontPermissions(bytes: ByteArray, embedding: PdfFontEmbedding) {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (bytes.size < 12 || buffer.getInt(0) != 0x00010000) fail(PdfFailureCode.INVALID_RESOURCE, "Only standalone TrueType outlines are supported")
        val count = buffer.getShort(4).toInt() and 65535
        if (count !in 1..256 || 12L + count * 16L > bytes.size) fail(PdfFailureCode.INVALID_RESOURCE, "Invalid TrueType directory")
        var permissions: Int? = null
        repeat(count) { index ->
            val position = 12 + index * 16
            val offset = buffer.getInt(position + 8).toLong() and 0xFFFFFFFFL
            val length = buffer.getInt(position + 12).toLong() and 0xFFFFFFFFL
            if (offset > bytes.size.toLong() - length) fail(PdfFailureCode.INVALID_RESOURCE, "Invalid font table bounds")
            if (buffer.getInt(position) == 0x4F532F32) {
                if (permissions != null || length < 10) fail(PdfFailureCode.INVALID_RESOURCE, "Invalid OS/2 font permissions table")
                permissions = buffer.getShort(offset.toInt() + 8).toInt() and 65535
            }
        }
        val flags = permissions ?: fail(PdfFailureCode.INVALID_RESOURCE, "Font has no embedding-permissions table")
        if ((flags and 15) !in setOf(0, 4, 8) || flags and 0x0200 != 0 || flags and 0x0100 != 0 && embedding == PdfFontEmbedding.SUBSET) {
            fail(PdfFailureCode.FONT_EMBEDDING_FORBIDDEN, "Font permission flags do not allow the requested outline embedding")
        }
    }

    private fun pngHasTransparency(bytes: ByteArray): Boolean {
        if (bytes.size < 33) fail(PdfFailureCode.INVALID_RESOURCE, "Invalid PNG header")
        if ((bytes[25].toInt() and 255) in setOf(4, 6)) return true
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        var offset = 8L
        while (offset + 12 <= bytes.size) {
            val length = buffer.getInt(offset.toInt()).toLong() and 0xFFFFFFFFL
            if (offset + length + 12 > bytes.size) fail(PdfFailureCode.INVALID_RESOURCE, "Invalid PNG chunk bounds")
            if (buffer.getInt(offset.toInt() + 4) == 0x74524E53) return true
            offset += length + 12
        }
        return false
    }

    private fun naturalMetrics(bytes: ByteArray): FloatArray? {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        fun table(tag: Int, minimum: Int): Int? {
            val entries = (0 until (buffer.getShort(4).toInt() and 65535)).filter { buffer.getInt(12 + it * 16) == tag }
            if (entries.size != 1) return null
            val position = 12 + entries.single() * 16
            if (buffer.getInt(position + 12) < minimum) return null
            return buffer.getInt(position + 8)
        }
        val head = table(0x68656164, 20) ?: return null
        val hhea = table(0x68686561, 10) ?: return null
        val units = buffer.getShort(head + 18).toInt() and 65535
        val ascent = buffer.getShort(hhea + 4).toInt()
        val descent = -buffer.getShort(hhea + 6).toInt()
        val gap = buffer.getShort(hhea + 8).toInt()
        if (units !in 16..16_384 || ascent <= 0 || descent < 0 || gap < 0) return null
        val line = floatArrayOf(ascent.toFloat() / units, descent.toFloat() / units, gap.toFloat() / units)
        val post = table(0x706F7374, 12) ?: return line
        val underlinePosition = buffer.getShort(post + 8).toInt()
        val underlineThickness = buffer.getShort(post + 10).toInt()
        if (underlinePosition !in -units..0 || underlineThickness !in 1..units / 4) return line
        return line + floatArrayOf(underlinePosition.toFloat() / units, underlineThickness.toFloat() / units)
    }

    private fun validatePng(bytes: ByteArray) {
        val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        if (!bytes.copyOfRange(0, 8).contentEquals(signature)) fail(PdfFailureCode.INVALID_RESOURCE, "Only PNG images are supported")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (buffer.getInt(8) != 13 || buffer.getInt(12) != 0x49484452) fail(PdfFailureCode.INVALID_RESOURCE, "Missing PNG image header")
        val width = buffer.getInt(16)
        val height = buffer.getInt(20)
        if (width !in 1..8192 || height !in 1..8192 || width.toLong() * height > 16_000_000) {
            fail(PdfFailureCode.RESOURCE_LIMIT, "PNG decoded dimensions exceed the foundation limit")
        }
        if (bytes.size < 33) fail(PdfFailureCode.INVALID_RESOURCE, "Incomplete PNG image header")
    }

    private class BoundedOutput(private val control: PdfRenderControl) : ByteArrayOutputStream() {
        override fun write(value: Int) { limit(1); super.write(value) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) { limit(length); super.write(bytes, offset, length) }
        private fun limit(length: Int) {
            control.checkActive()
            if (length < 0 || length.toLong() > minOf(16_777_216L, control.maxOutputBytes) - count) fail(PdfFailureCode.RESOURCE_LIMIT, "PDF output byte budget exceeded")
        }
    }

    private fun fail(code: PdfFailureCode, message: String): Nothing = throw PdfRenderException(code, message)
}
