package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Floating page-band objects keep physical coordinates independently of the band's text origin. */
internal object PageBandObjects {
    data class CellPicture(val picture: PdfAnchoredImage, val left: Float, val top: Float)

    fun place(images: List<Pair<PdfAnchoredImage, Float>>, boxes: List<Pair<PdfTextBox, Float>>,
              origin: Float, page: PdfPage, measurer: TextMeasurer, foreground: Boolean = false,
              header: Boolean = false, cellPictures: List<CellPicture> = emptyList()): List<PositionedElement> {
        val result = mutableListOf<PositionedElement>()
        for ((image, paragraphTop) in images) {
            PageLayouter.checkCancellation()
            if (image.wrap != PdfImageWrap.NONE) unsupported("Wrapped floating images in page bands are not qualified")
            val top = image.top + if (image.anchor == PdfBoxAnchor.PARAGRAPH) origin + paragraphTop else 0f
            if (image.left < 0 || image.left + image.image.width > page.width + .001f || top < -.001f || top + image.image.height > page.height + .001f) overflow()
            result += PositionedImage(image.image, image.left, top, foreground = foreground, pageY = true, drawingOrder = image.drawingOrder, link = image.link)
        }
        for ((anchor, baseLeft, baseTop) in cellPictures) {
            PageLayouter.checkCancellation()
            val image = anchor.image
            val left = baseLeft + anchor.left
            val top = origin + baseTop + anchor.top
            val cropLeft = (-left).coerceAtLeast(0f)
            val cropTop = (-top).coerceAtLeast(0f)
            val cropRight = (left + image.width - page.width).coerceAtLeast(0f)
            val cropBottom = (top + image.height - page.height).coerceAtLeast(0f)
            if (image.width - cropLeft - cropRight < 1f || image.height - cropTop - cropBottom < 1f) overflow()
            val visible = image.withPhysicalCrop(cropLeft, cropTop, cropRight, cropBottom)
            result += PositionedImage(visible, left + cropLeft, top + cropTop, pageY = true, drawingOrder = anchor.drawingOrder)
        }
        val backdrop = if (header) boxes.singleOrNull { (box, paragraphTop) ->
            paragraphTop == 0f && isHeaderBackdrop(box, origin, page)
        } else null
        for (entry in boxes) {
            val (box, paragraphTop) = entry
            PageLayouter.checkCancellation()
            if (box.wrap != PdfBoxWrap.NONE) unsupported("Wrapped text frames in page bands are not qualified")
            result += if (entry === backdrop) paintHeaderBackdrop(box, origin, page)
                else BoxLayouter.layout(box, page, origin + paragraphTop, measurer).copy(pageY = true)
        }
        // In Word a full-page header picture stays within its story's stack, underneath body content.
        val cover = if (header && boxes.isEmpty() && images.all { (image, _) ->
            image.anchor == PdfBoxAnchor.PAGE && image.drawingOrder != null
        } && cellPictures.all { it.picture.drawingOrder != null }) result.filterIsInstance<PositionedImage>().singleOrNull { pageCover(it, page) } else null
        val bounds = result.map(::bounds)
        for (index in bounds.indices) for (other in 0 until index) {
            val a = bounds[index]; val b = bounds[other]
            if (cover != null && (result[index] === cover || result[other] === cover) ||
                (result[index] as? PositionedBox)?.headerBackdrop == true ||
                (result[other] as? PositionedBox)?.headerBackdrop == true) continue
            if (a[0] < b[2] - .001f && a[2] > b[0] + .001f && a[1] < b[3] - .001f && a[3] > b[1] + .001f)
                unsupported("Overlapping floating page-band objects are not qualified")
        }
        return if (cover == null) result else result.map { (it as PositionedImage).copy(headerUnderlay = true) }
    }

    fun occupied(band: PdfPageBand, footer: Boolean, page: PdfPage, content: Pair<List<PositionedElement>, Float>): Float {
        var required = band.offset + content.second
        for (element in content.first) {
            if (element is PositionedImage && element.headerUnderlay && pageCover(element, page) ||
                element is PositionedBox && element.headerBackdrop) continue
            if (element is PositionedImage && element.pageY || element is PositionedBox && element.pageY) {
                val bounds = bounds(element)
                required = maxOf(required, if (footer) page.height - bounds[1] else bounds[3])
            }
        }
        return required
    }

    fun isHeaderBackdrop(box: PdfTextBox, headerOrigin: Float, page: PdfPage): Boolean {
        val top = headerOrigin + box.top
        val bottom = top + box.height
        return box.anchor == PdfBoxAnchor.PARAGRAPH && box.drawingOrder?.layer == PdfDrawingLayer.BACKGROUND &&
            box.wrap == PdfBoxWrap.NONE && box.rotation == 0f && box.autoFit == null && box.border == null &&
            box.fill != null && (box.semantics == null || box.semantics.role == PdfSemanticRole.FRAME &&
                box.semantics.parentId == null) && box.left in -72f..0f && top in -1f..0f &&
            box.left + box.width in (page.width - .001f)..(page.width + 72f) && bottom in 1f..(page.height / 4f) &&
            box.paragraphs.all { p -> p.background == null && p.borders == null && p.anchoredImages.isEmpty() &&
                p.lines.isEmpty() && p.endDestinations.isEmpty() && p.contents == null && p.listLabel == null &&
                p.runs.all { it is PdfTextRun && it.text.isBlank() && it.navigation == null &&
                    !it.style.underline && !it.style.strikethrough && it.style.background == null }
            }
    }

    private fun paintHeaderBackdrop(box: PdfTextBox, origin: Float, page: PdfPage): PositionedBox {
        val height = origin + box.top + box.height
        val clipped = PdfTextBox(0f, 0f, page.width, height, box.paragraphs, PdfBoxAnchor.PAGE, padding = PdfMargins(0f, 0f, 0f, 0f), fill = box.fill)
            .withDrawingOrder(box.drawingOrder)
        return PositionedBox(clipped, 0f, 0f, listOf(PositionedRectangle(0f, 0f, page.width, height, box.fill, null)),
            pageY = true, headerBackdrop = true)
    }

    private fun pageCover(image: PositionedImage, page: PdfPage): Boolean =
        image.link == null && image.x <= 2f && image.top <= 2f &&
            image.x + image.image.width >= page.width - 2f && image.top + image.image.height >= page.height - 2f

    private fun bounds(element: PositionedElement): FloatArray = when (element) {
        is PositionedImage -> floatArrayOf(element.x, element.top, element.x + element.image.width, element.top + element.image.height)
        is PositionedBox -> {
            val angle = Math.toRadians(element.box.rotation.toDouble())
            val dx = ((abs(cos(angle)) * element.box.width + abs(sin(angle)) * element.box.height) / 2).toFloat()
            val dy = ((abs(sin(angle)) * element.box.width + abs(cos(angle)) * element.box.height) / 2).toFloat()
            val stroke = (element.box.border?.width ?: 0f) / 2
            val x = element.x + element.box.width / 2; val y = element.top + element.box.height / 2
            val boxBottom = y + dy + stroke
            val printedBottom = if (element.contentHeight > element.box.height)
                maxOf(boxBottom, element.top + element.contentHeight) else boxBottom
            floatArrayOf(x - dx - stroke, y - dy - stroke, x + dx + stroke, printedBottom)
        }
        else -> error("Not a floating page-band object")
    }

    private fun unsupported(message: String): Nothing = throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, message)

    private fun overflow(): Nothing = throw PdfRenderException(PdfFailureCode.CONTENT_OVERFLOW, "Floating page-band object exceeds the physical page")

}
