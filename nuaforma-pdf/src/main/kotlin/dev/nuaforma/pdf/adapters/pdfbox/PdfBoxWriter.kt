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

internal object PdfBoxWriter {
    fun render(document: PdfDocument, suppliedFonts: List<PdfFont>): ByteArray {
        PageLayouter.checkCancellation()
        PDDocument().use { pdf ->
            pdf.version = 1.7f
            val lineMetrics = mutableMapOf<String, FloatArray>()
            val fonts = suppliedFonts.associate { resource ->
                val bytes = resource.bytes()
                validateFontPermissions(bytes)
                naturalMetrics(bytes)?.let { lineMetrics[resource.id] = it }
                val font = try {
                    PDType0Font.load(pdf, ByteArrayInputStream(bytes), true)
                } catch (_: IOException) {
                    fail(PdfFailureCode.INVALID_RESOURCE, "Cannot load the supplied TrueType font")
                }
                resource.id to font
            }
            val measurer = TextMeasurer { text, style ->
                val font = fonts[style.fontId] ?: fail(PdfFailureCode.FONT_UNAVAILABLE, "Referenced font is not supplied")
                val width = try {
                    font.getStringWidth(text) * style.size / 1000f
                } catch (_: IllegalArgumentException) {
                    fail(PdfFailureCode.MISSING_GLYPH, "The supplied font cannot encode requested text")
                }
                val box = font.fontDescriptor.fontBoundingBox
                TextMetrics(width, maxOf(font.fontDescriptor.ascent, box.upperRightY) * style.size / 1000f,
                    maxOf(-font.fontDescriptor.descent, -box.lowerLeftY) * style.size / 1000f,
                    lineMetrics[style.fontId]?.get(0)?.times(style.size) ?: Float.NaN,
                    lineMetrics[style.fontId]?.get(1)?.times(style.size) ?: Float.NaN,
                    lineMetrics[style.fontId]?.get(2)?.times(style.size) ?: Float.NaN)
            }
            val layouts = mutableListOf<PageLayout>()
            val sequencePages = mutableListOf<Int>()
            for (sequence in document.pageSequences) {
                PageLayouter.checkCancellation()
                val remaining = document.maxPages - layouts.size
                if (remaining <= 0) fail(PdfFailureCode.RESOURCE_LIMIT, "Document page budget exceeded")
                val single = PdfDocument(sequence.page, sequence.blocks, sequence.flow)
                val pages = if (sequence.flow == null) listOf(PageLayouter.layout(single, measurer))
                    else FlowLayouter.layout(single, measurer, remaining)
                layouts += pages
                sequencePages += pages.size
            }
            var offset = 0
            for ((index, sequence) in document.pageSequences.withIndex()) {
                val count = sequencePages[index]
                sequence.flow?.let { policy ->
                    val decorated = FlowLayouter.decorate(layouts.subList(offset, offset + count), policy, offset, layouts.size, measurer)
                    for ((local, layout) in decorated.withIndex()) layouts[offset + local] = layout
                }
                offset += count
            }
            fun decorationSize(elements: List<PositionedElement>): Long = elements.sumOf { element -> when (element) {
                is PositionedText -> if (element.decoration) element.text.length.toLong() else 0L
                is PositionedBox -> decorationSize(element.elements)
                else -> 0L
            } }
            if (layouts.sumOf { decorationSize(it.elements) } > 100_000) fail(PdfFailureCode.RESOURCE_LIMIT, "Generated leader budget exceeded")
            val usedFonts = linkedSetOf<String>()
            val images = java.util.IdentityHashMap<PdfImage, PDImageXObject>()
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
                    fun paint(elements: List<PositionedElement>, canvasHeight: Float) {
                        for (element in elements) {
                            PageLayouter.checkCancellation()
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
                                    paint(element.elements, element.box.height)
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
                                is PositionedText -> {
                                    if (element.decoration) command("BMC", COSName.ARTIFACT)
                                    element.style.languageTag?.let { language ->
                                        val properties = COSDictionary().apply { setString(COSName.LANG, language) }
                                        command("BDC", COSName.getPDFName("Span"), properties)
                                    }
                                    val color = element.style.color
                                    val font = fonts.getValue(element.style.fontId)
                                    usedFonts += element.style.fontId
                                    element.text.codePoints().forEach { font.addToSubset(it) }
                                    command("BT")
                                    command("Tf", page.resources.add(font), number(element.style.size))
                                    command("rg", number(color.red / 255f), number(color.green / 255f), number(color.blue / 255f))
                                    command("Tm", number(1f), number(0f), number(0f), number(1f), number(element.x), number(canvasHeight - element.baseline))
                                    // Write the same qualified cmap glyphs used for measurement, without a second GSUB pass.
                                    command("Tj", COSString(font.encode(element.text)))
                                    command("ET")
                                    if (element.style.languageTag != null) command("EMC")
                                    if (element.decoration) command("EMC")
                                }
                                is PositionedImage -> {
                                    val image = images.getOrPut(element.image) {
                                        val bytes = element.image.bytes()
                                        validatePng(bytes)
                                        try { PDImageXObject.createFromByteArray(pdf, bytes, "image") }
                                        catch (_: IOException) { fail(PdfFailureCode.INVALID_RESOURCE, "Cannot decode the supplied PNG") }
                                    }
                                    command("q")
                                    command("cm", number(element.image.width), number(0f), number(0f), number(element.image.height),
                                        number(element.x), number(canvasHeight - element.top - element.image.height))
                                    command("Do", page.resources.add(image))
                                    command("Q")
                                }
                            }
                        }
                    }
                    paint(layout.elements, layout.page.height)
                }
            }
            usedFonts.forEach { fonts.getValue(it).subset() }
            val output = BoundedOutput()
            pdf.save(output)
            PageLayouter.checkCancellation()
            return output.toByteArray()
        }
    }

    private fun validateFontPermissions(bytes: ByteArray) {
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
        if ((flags and 15) !in setOf(0, 4, 8) || flags and 0x0300 != 0) {
            fail(PdfFailureCode.FONT_EMBEDDING_FORBIDDEN, "Font permission flags do not allow the foundation's subset outline embedding")
        }
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
        return floatArrayOf(ascent.toFloat() / units, descent.toFloat() / units, gap.toFloat() / units)
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
    }

    private class BoundedOutput : ByteArrayOutputStream() {
        override fun write(value: Int) { limit(1); super.write(value) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) { limit(length); super.write(bytes, offset, length) }
        private fun limit(length: Int) {
            if (length < 0 || count.toLong() + length > 16_777_216) fail(PdfFailureCode.RESOURCE_LIMIT, "PDF output exceeds the foundation limit")
        }
    }

    private fun fail(code: PdfFailureCode, message: String): Nothing = throw PdfRenderException(code, message)
}
