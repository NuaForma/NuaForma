package dev.nuaforma.pdf.adapters.pdfbox

import dev.nuaforma.pdf.*
import dev.nuaforma.pdf.internal.*
import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSFloat
import org.apache.pdfbox.cos.COSInteger
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

    fun render(document: PdfDocument, suppliedFonts: List<PdfFont>, options: PdfOutputOptions, control: PdfRenderControl, watermark: PdfWatermark?): ByteArray {
        control.checkActive()
        PDDocument().use { pdf ->
            pdf.version = if (options.profile == PdfOutputProfile.PDFA_1B) 1.4f else 1.7f
            ArchivalMetadata.apply(pdf, options)
            val navigation = PdfNavigationWriter(pdf)
            val lineMetrics = mutableMapOf<String, FloatArray>()
            val fullBmpFonts = mutableSetOf<String>()
            val privateGlyphFonts = mutableSetOf<String>()
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
                if (options.profile == PdfOutputProfile.PDFA_1B && resource.embedding == PdfFontEmbedding.FULL) {
                    // PDFBox's full-font cmap includes unused supplementary characters and raises
                    // the catalog to 1.5. Replace that cmap with used BMP clusters below; no font
                    // outlines are removed. Undo only this known font-load version side effect.
                    font.cosObject.removeItem(COSName.TO_UNICODE)
                    if (pdf.version == 1.5f) pdf.documentCatalog.version = "1.4"
                    fullBmpFonts += resource.id
                }
                resource.id to font
            }
            val shaper = FontShaper(suppliedFonts, fonts, control)
            val encoding = GlyphEncoding(fonts, { shaper.enabled(it) || it in fullBmpFonts || it in privateGlyphFonts }, control,
                options.profile == PdfOutputProfile.PDFA_1B)
            val measurer = object : TextMeasurer {

              override fun measure(text: String, style: PdfTextStyle): TextMetrics {
                control.checkActive()
                val font = fonts[style.fontId] ?: fail(PdfFailureCode.FONT_UNAVAILABLE, "Referenced font is not supplied")
                val width = try {
                    if (style.fontFeatures.active) shaper.shape(text, style).advance else {
                        val input = shaper.fontInput(text, style.fontId)
                        if (style.characterSpacing < 0f) input.codePoints().forEach { point ->
                            val glyphWidth = font.getStringWidth(String(Character.toChars(point))) * style.size / 1000f * style.horizontalScale
                            if (glyphWidth + style.characterSpacing < 0f)
                                fail(PdfFailureCode.UNSUPPORTED_TEXT, "Character spacing reverses glyph advances")
                        }
                        font.getStringWidth(input) * style.size / 1000f * style.horizontalScale + text.codePointCount(0, text.length) * style.characterSpacing
                    }
                } catch (_: IllegalArgumentException) {
                    fail(PdfFailureCode.MISSING_GLYPH, "The supplied font cannot encode requested text")
                }
                val box = font.fontDescriptor.fontBoundingBox
                val metricSize = style.lineMetricSize ?: style.size
                return TextMetrics(width, maxOf(font.fontDescriptor.ascent, box.upperRightY) * style.size / 1000f + style.baselineShift,
                    maxOf(-font.fontDescriptor.descent, -box.lowerLeftY) * style.size / 1000f - style.baselineShift,
                    lineMetrics[style.fontId]?.get(0)?.times(metricSize)?.plus(style.baselineShift) ?: Float.NaN,
                    lineMetrics[style.fontId]?.get(1)?.times(metricSize)?.minus(style.baselineShift) ?: Float.NaN,
                    lineMetrics[style.fontId]?.get(2)?.times(metricSize) ?: Float.NaN)
              }

              override fun ink(text: String, style: PdfTextStyle): TextMetrics {
                val font = fonts[style.fontId] ?: fail(PdfFailureCode.FONT_UNAVAILABLE, "Referenced font is not supplied")
                val bounds = GlyphInkBounds.measure(font, shaper.inkShape(text, style), style, lineMetrics[style.fontId], control)
                return measure(text, style).copy(ascent = bounds.first, descent = bounds.second)
              }

              override fun gap(before: String, spaces: String, after: String, style: PdfTextStyle): TextMetrics {
                val width = shaper.shape(before + spaces + after, style).advance - shaper.shape(before, style).advance - shaper.shape(after, style).advance
                if (!width.isFinite() || width < 0) fail(PdfFailureCode.INVALID_RESOURCE, "Invalid contextual font spacing")
                return measure(spaces, style).copy(width = width)
              }

            }
            val composed = if (ReferenceLayouter.active(document)) ReferenceLayouter.layout(document, measurer, control)
                else DocumentLayouter.plain(document, measurer, control)
            DocumentLayouter.validatePlacedLinks(composed)
            val layouts = composed.pages
            for (layout in layouts) for (text in DocumentLayouter.texts(layout.elements)) {
                control.checkActive()
                if (text.text.codePoints().anyMatch { it in 0xE000..0xF8FF } ||
                    shaper.fontInput(text.text, text.style.fontId) != text.text) privateGlyphFonts += text.style.fontId
            }
            watermark?.let { if (it.text.codePoints().anyMatch { point -> point in 0xE000..0xF8FF }) privateGlyphFonts += it.fontId }

            fun decorationSize(elements: List<PositionedElement>): Long = elements.sumOf { element -> when (element) {
                is PositionedText -> if (element.decoration) element.text.length.toLong() else 0L
                is PositionedBox -> decorationSize(element.elements)
                else -> 0L
            } }

            if (layouts.sumOf { decorationSize(it.elements) } > 100_000) fail(PdfFailureCode.RESOURCE_LIMIT, "Generated leader budget exceeded")

            fun borderSize(elements: List<PositionedElement>): Int = elements.sumOf { when (it) { is PositionedTableBorder -> 1; is PositionedBox -> borderSize(it.elements); else -> 0 } }

            if (layouts.sumOf { borderSize(it.elements) } > 20_000) fail(PdfFailureCode.RESOURCE_LIMIT, "Table border budget exceeded")

            fun lineSize(elements: List<PositionedElement>): Int = elements.sumOf { when (it) {
                is PositionedStroke -> 1
                is PositionedVector -> it.run.vector.paths.sumOf { path -> path.points.size }
                is PositionedBox -> lineSize(it.elements)
                else -> 0
            } }

            if (layouts.sumOf { lineSize(it.elements) } > 20_000) fail(PdfFailureCode.RESOURCE_LIMIT, "Vector line budget exceeded")
            val usedFonts = linkedSetOf<String>()
            val decoration = watermark?.let { requested ->
                val bodyCharacters = document.allParagraphs().sumOf { paragraph -> paragraph.runs.filterIsInstance<PdfTextRun>().sumOf { it.text.length.toLong() } }
                if (requested.text.length.toLong() * layouts.size + bodyCharacters > 100_000)
                    fail(PdfFailureCode.RESOURCE_LIMIT, "Watermark and document text exceed the cumulative budget")
                val font = fonts[requested.fontId] ?: fail(PdfFailureCode.FONT_UNAVAILABLE, "Watermark font is not supplied")
                usedFonts += requested.fontId
                PdfWatermarkPainter(requested, font, minOf(400f, document.pageSequences.minOf { minOf(it.page.width, it.page.height) } * 0.65f)) {
                    encoding.normal(requested.fontId, it)
                }
            }
            val images = java.util.IdentityHashMap<Any, PDImageXObject>()
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

                    fun paint(elements: List<PositionedElement>, canvasHeight: Float, canvasWidth: Float, originX: Float = 0f, originTop: Float = 0f) {

                        fun drawingOrder(element: PositionedElement): PdfDrawingOrder? = when (element) {
                            is PositionedBox -> element.box.drawingOrder
                            is PositionedImage -> element.drawingOrder
                            else -> null
                        }

                        fun layer(element: PositionedElement): Int = when {
                            element is PositionedImage && element.headerUnderlay -> {
                                if (element.drawingOrder?.layer == PdfDrawingLayer.BACKGROUND) -4 else -3
                            }
                            else -> drawingOrder(element)?.let {
                                if (it.layer == PdfDrawingLayer.BACKGROUND) -2 else 2
                            } ?: when {
                                element is PositionedStroke -> when (element.layer) { StrokeLayer.FLOW -> 0; StrokeLayer.BACKGROUND -> -1; StrokeLayer.FOREGROUND -> 1 }
                                element is PositionedImage && element.foreground -> 1
                                else -> 0
                            }
                        }

                        val ordered = elements.sortedWith(compareBy<PositionedElement>(::layer)
                            .thenBy { drawingOrder(it)?.stackingOrder ?: 0L }.thenBy { drawingOrder(it)?.tieBreak ?: 0L })

                        fun backgroundGroup(value: PositionedElement?) = value is PositionedText || value is PositionedStroke && value.layer != StrokeLayer.BACKGROUND ||
                            value is PositionedTableBorder || value is PositionedRectangle && value.paragraph != null

                        for ((elementIndex, element) in ordered.withIndex()) {
                            control.checkActive()
                            if (backgroundGroup(element) && !backgroundGroup(ordered.getOrNull(elementIndex - 1))) {
                                var backgroundIndex = elementIndex
                                val group = mutableListOf<PositionedElement>()
                                while (backgroundIndex < ordered.size && backgroundGroup(ordered[backgroundIndex])) group += ordered[backgroundIndex++]
                                for (fill in group.filterIsInstance<PositionedRectangle>()) {
                                    control.checkActive()
                                    val clipped = ParagraphBackgrounds.clipped(fill) ?: continue
                                    ParagraphBackgrounds.validate(clipped, canvasWidth, canvasHeight)
                                    command("BMC", COSName.ARTIFACT)
                                    paint(listOf(clipped.copy(paragraph = null)), canvasHeight, canvasWidth, originX, originTop)
                                    command("EMC")
                                }
                                for (text in group.filterIsInstance<PositionedText>()) {
                                    control.checkActive()
                                    val background = TextBackgrounds.rectangle(text, canvasWidth, canvasHeight) ?: continue
                                    command("BMC", COSName.ARTIFACT)
                                    paint(listOf(background), canvasHeight, canvasWidth, originX, originTop)
                                    command("EMC")
                                }
                            }
                            when (element) {
                                is PositionedVector -> {
                                    val run = element.run
                                    if (element.x < -.001f || element.top < -.001f ||
                                        element.x + run.width > canvasWidth + .001f || element.top + run.height > canvasHeight + .001f)
                                        fail(PdfFailureCode.CONTENT_OVERFLOW, "An inline vector exceeds its physical page or frame")
                                    command("BMC", COSName.ARTIFACT)
                                    command("q")
                                    command("RG", number(run.color.red / 255f), number(run.color.green / 255f), number(run.color.blue / 255f))
                                    command("w", number(run.width * run.vector.strokeWidth / 1000f))
                                    command("J", COSInteger.ONE); command("j", COSInteger.ONE)
                                    command("d", COSArray(), number(0f))
                                    for (path in run.vector.paths) {
                                        control.checkActive()
                                        path.points.forEachIndexed { index, point ->
                                            command(if (index == 0) "m" else "l", number(element.x + point.x * run.width / 1000f),
                                                number(canvasHeight - element.top - point.y * run.height / 1000f))
                                        }
                                        if (path.closed) command("h")
                                        command("S")
                                    }
                                    command("Q"); command("EMC")
                                }
                                is PositionedStroke -> {
                                    val stroke = element.stroke
                                    val extent = stroke.extent / 2
                                    val horizontalDelta = element.endX - element.x
                                    val verticalDelta = element.endY - element.top
                                    val length = kotlin.math.hypot(horizontalDelta.toDouble(), verticalDelta.toDouble()).toFloat()
                                    val bleedX = if (length == 0f) extent else kotlin.math.abs(verticalDelta) / length * extent
                                    val bleedY = if (length == 0f) extent else kotlin.math.abs(horizontalDelta) / length * extent
                                    if (minOf(element.x, element.endX) < bleedX - .001f || maxOf(element.x, element.endX) > canvasWidth - bleedX + .001f ||
                                        minOf(element.top, element.endY) < bleedY - .001f || maxOf(element.top, element.endY) > canvasHeight - bleedY + .001f)
                                        fail(PdfFailureCode.CONTENT_OVERFLOW, "A vector line exceeds its physical page or frame")
                                    command("BMC", COSName.ARTIFACT)
                                    command("q")
                                    command("RG", number(stroke.color.red / 255f), number(stroke.color.green / 255f), number(stroke.color.blue / 255f))
                                    command("w", number(stroke.width))
                                    val dash = if (stroke.dashOn != null && stroke.dashOff != null) floatArrayOf(stroke.dashOn, stroke.dashOff) else
                                        when (stroke.style) { PdfLineStyle.DASHED -> floatArrayOf(3 * stroke.width, stroke.width); PdfLineStyle.DOTTED -> floatArrayOf(stroke.width, stroke.width); else -> floatArrayOf() }
                                    command("d", COSArray().also { a -> dash.forEach { a.add(number(it)) } }, number(0f))
                                    val bands = when (stroke.style) {
                                        PdfLineStyle.DOUBLE -> listOf(-stroke.width to stroke.width, stroke.width to stroke.width)
                                        PdfLineStyle.THIN_THICK_SMALL_GAP, PdfLineStyle.THICK_THIN_SMALL_GAP -> {
                                            val fixed = COMPOUND_BORDER_SMALL_GAP
                                            val variableFirst = stroke.style == PdfLineStyle.THIN_THICK_SMALL_GAP
                                            val first = if (variableFirst) stroke.width else fixed
                                            val second = if (variableFirst) fixed else stroke.width
                                            // Word orders bands from top to bottom or left to right on the page.
                                            val direction = if (kotlin.math.abs(horizontalDelta) >= kotlin.math.abs(verticalDelta))
                                                horizontalDelta else -verticalDelta
                                            val sign = if (direction < 0f) 1f else -1f
                                            listOf(sign * (extent - first / 2) to first, -sign * (extent - second / 2) to second)
                                        }
                                        else -> listOf(0f to stroke.width)
                                    }
                                    var penWidth = stroke.width
                                    for ((offset, bandWidth) in bands) {
                                        if (bandWidth != penWidth) {
                                            command("w", number(bandWidth))
                                            penWidth = bandWidth
                                        }
                                        val dx = if (length == 0f) 0f else -verticalDelta / length * offset
                                        val dy = if (length == 0f) 0f else horizontalDelta / length * offset
                                        command("m", number(element.x + dx), number(canvasHeight - element.top - dy))
                                        command("l", number(element.endX + dx), number(canvasHeight - element.endY - dy))
                                        command("S")
                                    }
                                    command("Q"); command("EMC")
                                }
                                is PositionedBox -> {
                                    val angle = Math.toRadians(element.box.rotation.toDouble())
                                    val c = kotlin.math.cos(angle).toFloat()
                                    val s = kotlin.math.sin(angle).toFloat()
                                    val cx = element.box.width / 2
                                    val cy = element.box.height / 2
                                    command("q")
                                    val extension = element.contentHeight - element.box.height
                                    command("cm", number(c), number(-s), number(s), number(c),
                                        number(element.x + cx - c * cx - s * cy),
                                        number(canvasHeight - element.top - cy + s * cx - c * cy - extension))
                                    paint(element.elements, element.contentHeight, element.box.width,
                                        originX + element.x, originTop + element.top)
                                    command("Q")
                                }
                                is PositionedRectangle -> {
                                    if (element.paragraph != null) continue
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
                                is PositionedDestination -> element.targets.forEach { target ->
                                    navigation.addDestination(target, page, element.x, canvasHeight - element.top)
                                }
                                is PositionedText -> {
                                    if (element.navigation != null) {
                                        val physical = if (originX == 0f && originTop == 0f) element
                                            else element.copy(x = element.x + originX, baseline = element.baseline + originTop)
                                        navigation.add(physical, page, measurer.measure(element.text, element.style))
                                    }
                                    if (element.decoration) command("BMC", COSName.ARTIFACT)
                                    element.style.languageTag?.let { language ->
                                        val properties = COSDictionary().apply { setString(COSName.LANG, language) }
                                        command("BDC", COSName.getPDFName("Span"), properties)
                                    }
                                    val color = element.style.color
                                    val font = fonts.getValue(element.style.fontId)
                                    usedFonts += element.style.fontId
                                    val input = shaper.fontInput(element.text, element.style.fontId)
                                    if (font.willBeSubset()) input.codePoints().forEach { font.addToSubset(it) }
                                    if (element.style.syntheticBold) command("q")
                                    command("BT")
                                    command("Tf", page.resources.add(font), number(element.style.size))
                                    command("Tc", number(if (element.style.fontFeatures.active) 0f else element.style.characterSpacing / element.style.horizontalScale))
                                    command("rg", number(color.red / 255f), number(color.green / 255f), number(color.blue / 255f))
                                    if (element.style.syntheticBold) {
                                        command("RG", number(color.red / 255f), number(color.green / 255f), number(color.blue / 255f))
                                        command("w", number(element.style.size / 35f))
                                        command("Tr", COSInteger.get(2))
                                    }
                                    command("Tm", number(element.style.horizontalScale), number(0f), number(0f), number(1f), number(element.x), number(canvasHeight - (element.baseline - element.style.baselineShift)))
                                    if (element.style.fontFeatures.active) {
                                        val shape = shaper.shape(element.text, element.style)
                                        encoding.record(element.style.fontId, shape)
                                        for (glyph in shape.glyphs) {
                                            control.checkActive()
                                            command("Tm", number(element.style.horizontalScale), number(0f), number(0f), number(1f), number(element.x + glyph.x),
                                                number(canvasHeight - (element.baseline - element.style.baselineShift + glyph.y)))
                                            command("Tj", COSString(encoding.glyph(element.style.fontId, glyph)))
                                        }
                                    } else command("Tj", COSString(encoding.normal(element.style.fontId, element.text, input)))
                                    command("ET")
                                    if (element.style.syntheticBold) {
                                        command("Q")
                                        command("rg", number(color.red / 255f), number(color.green / 255f), number(color.blue / 255f))
                                    }
                                    if (element.style.underline) {
                                        val metrics = lineMetrics[element.style.fontId]
                                        val position = metrics?.getOrNull(3)?.takeIf { it.isFinite() } ?: fail(PdfFailureCode.INVALID_RESOURCE, "Font has no qualified underline metrics")
                                        val thickness = metrics.getOrNull(4)?.takeIf { it.isFinite() } ?: fail(PdfFailureCode.INVALID_RESOURCE, "Font has no qualified underline thickness")
                                        val underline = underlineGeometry(element.style, position, thickness)
                                        val depth = if (element.style.thickUnderline) underline.bottom / element.style.size else position - thickness
                                        if (depth < font.fontDescriptor.fontBoundingBox.lowerLeftY / 1000f)
                                            fail(PdfFailureCode.UNSUPPORTED_TEXT, "Underline exceeds the qualified font bounds")
                                        val underlineColor = element.style.underlineColor?.takeUnless { it == color }
                                        if (underlineColor != null) {
                                            command("q")
                                            command("rg", number(underlineColor.red / 255f), number(underlineColor.green / 255f),
                                                number(underlineColor.blue / 255f))
                                        }
                                        // OpenType post.underlinePosition denotes the top edge, unlike PostScript's center coordinate.
                                        command("re", number(element.x), number(canvasHeight - (element.baseline - element.style.baselineShift) + underline.bottom),
                                            number(element.trailingDecorationWidth ?: measurer.measure(element.text, element.style).width), number(underline.thickness))
                                        command("f")
                                        if (underlineColor != null) command("Q")
                                    }
                                    if (element.style.strikethrough) {
                                        val metrics = lineMetrics[element.style.fontId]
                                        val position = metrics?.getOrNull(5)?.takeIf { it.isFinite() } ?: fail(PdfFailureCode.INVALID_RESOURCE, "Font has no qualified strikeout metrics")
                                        val thickness = metrics.getOrNull(6)?.takeIf { it.isFinite() } ?: fail(PdfFailureCode.INVALID_RESOURCE, "Font has no qualified strikeout thickness")
                                        val box = font.fontDescriptor.fontBoundingBox
                                        if (position - thickness < box.lowerLeftY / 1000f || position > box.upperRightY / 1000f)
                                            fail(PdfFailureCode.UNSUPPORTED_TEXT, "Strikeout exceeds the qualified font bounds")
                                        command("re", number(element.x), number(canvasHeight - (element.baseline - element.style.baselineShift) + (position - thickness) * element.style.size),
                                            number(element.trailingDecorationWidth ?: measurer.measure(element.text, element.style).width), number(thickness * element.style.size))
                                        command("f")
                                    }
                                    if (element.style.languageTag != null) command("EMC")
                                    if (element.decoration) command("EMC")
                                }
                                is PositionedImage -> {
                                    if (element.requiresPhysicalBounds) {

                                        fun exceeds(edge: String, required: Float, available: Float): Nothing =
                                            fail(PdfFailureCode.CONTENT_OVERFLOW, "Inline image exceeds physical $edge boundary; required=$required pt; available=$available pt")

                                        val left = maxOf(element.x, element.clip?.left ?: element.x)
                                        val top = maxOf(element.top, element.clip?.top ?: element.top)
                                        val right = minOf(element.x + element.image.width, element.clip?.right ?: Float.POSITIVE_INFINITY)
                                        val bottom = minOf(element.top + element.image.height, element.clip?.bottom ?: Float.POSITIVE_INFINITY)
                                        if (left < right && top < bottom) {
                                            if (left < -.001f) exceeds("left", -left, 0f)
                                            if (top < -.001f) exceeds("top", -top, 0f)
                                            if (right > canvasWidth + .001f) exceeds("right", right - left, canvasWidth - left)
                                            if (bottom > canvasHeight + .001f) exceeds("bottom", bottom - top, canvasHeight - top)
                                        }
                                    }
                                    val image = images.getOrPut(element.image.rasterIdentity) {
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
                                    element.clip?.let { clip ->
                                        command("re", number(clip.left), number(canvasHeight - clip.bottom), number(clip.right - clip.left), number(clip.bottom - clip.top))
                                        command("W"); command("n")
                                    }
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
                                    element.link?.let { link ->
                                        navigation.addImage(page, link, originX + element.x, originTop + element.top,
                                            element.image.width, element.image.height)
                                    }
                                }
                            }
                        }
                        val tableBorders = TableBorderLayouter.rectangles(elements, canvasWidth, canvasHeight)
                        if (tableBorders.isNotEmpty()) paint(tableBorders, canvasHeight, canvasWidth, originX, originTop)
                    }

                    decoration?.paint(page, writer, layout.page.width, layout.page.height)
                    paint(layout.elements, layout.page.height, layout.page.width)
                    layout.pageFrame?.let { frame ->
                        val (top, right, bottom, left) = frame.offsets
                        val (width, color) = frame.border
                        command("BMC", COSName.ARTIFACT)
                        command("q")
                        command("RG", number(color.red / 255f), number(color.green / 255f), number(color.blue / 255f))
                        command("w", number(width))
                        command("j", COSInteger.ZERO)
                        command("re", number(left + width / 2), number(bottom + width / 2),
                            number(layout.page.width - left - right - width), number(layout.page.height - top - bottom - width))
                        command("S")
                        command("Q")
                        command("EMC")
                    }
                }
            }
            navigation.finish()
            usedFonts.map { fonts.getValue(it) }.filter { it.willBeSubset() }.forEach { it.subset() }
            encoding.finish(pdf)
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
        val metrics = line + FloatArray(4) { Float.NaN }
        table(0x706F7374, 12)?.let { post ->
            val position = buffer.getShort(post + 8).toInt()
            val thickness = buffer.getShort(post + 10).toInt()
            if (position in -units..0 && thickness in 1..units / 4) {
                metrics[3] = position.toFloat() / units; metrics[4] = thickness.toFloat() / units
            }
        }
        // OpenType OS/2 yStrikeoutSize/Position; position denotes the top edge of the stroke.
        table(0x4F532F32, 30)?.let { os2 ->
            val thickness = buffer.getShort(os2 + 26).toInt()
            val position = buffer.getShort(os2 + 28).toInt()
            if (position in -units..units && thickness in 1..units / 4) {
                metrics[5] = position.toFloat() / units; metrics[6] = thickness.toFloat() / units
            }
        }
        return metrics
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
