package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Floating page-band objects keep physical coordinates independently of the band's text origin. */
internal object PageBandObjects {

    fun place(images: List<Pair<PdfAnchoredImage, Float>>, boxes: List<Pair<PdfTextBox, Float>>,
              origin: Float, page: PdfPage, measurer: TextMeasurer, foreground: Boolean = false): List<PositionedElement> {
        val result = mutableListOf<PositionedElement>()
        for ((image, paragraphTop) in images) {
            PageLayouter.checkCancellation()
            if (image.wrap != PdfImageWrap.NONE) unsupported("Wrapped floating images in page bands are not qualified")
            val top = image.top + if (image.anchor == PdfBoxAnchor.PARAGRAPH) origin + paragraphTop else 0f
            if (image.left < 0 || image.left + image.image.width > page.width + .001f || top < -.001f || top + image.image.height > page.height + .001f) overflow()
            result += PositionedImage(image.image, image.left, top, foreground = foreground, pageY = true)
        }
        for ((box, paragraphTop) in boxes) {
            PageLayouter.checkCancellation()
            if (box.wrap != PdfBoxWrap.NONE) unsupported("Wrapped text frames in page bands are not qualified")
            result += BoxLayouter.layout(box, page, origin + paragraphTop, measurer).copy(pageY = true)
        }
        val bounds = result.map(::bounds)
        for (index in bounds.indices) for (other in 0 until index) {
            val a = bounds[index]; val b = bounds[other]
            if (a[0] < b[2] - .001f && a[2] > b[0] + .001f && a[1] < b[3] - .001f && a[3] > b[1] + .001f)
                unsupported("Overlapping floating page-band objects are not qualified")
        }
        return result
    }

    fun occupied(band: PdfPageBand, footer: Boolean, page: PdfPage, content: Pair<List<PositionedElement>, Float>): Float {
        var required = band.offset + content.second
        for (element in content.first) {
            if (element is PositionedImage && element.pageY || element is PositionedBox && element.pageY) {
                val bounds = bounds(element)
                required = maxOf(required, if (footer) page.height - bounds[1] else bounds[3])
            }
        }
        return required
    }

    private fun bounds(element: PositionedElement): FloatArray = when (element) {
        is PositionedImage -> floatArrayOf(element.x, element.top, element.x + element.image.width, element.top + element.image.height)
        is PositionedBox -> {
            val angle = Math.toRadians(element.box.rotation.toDouble())
            val dx = ((abs(cos(angle)) * element.box.width + abs(sin(angle)) * element.box.height) / 2).toFloat()
            val dy = ((abs(sin(angle)) * element.box.width + abs(cos(angle)) * element.box.height) / 2).toFloat()
            val stroke = (element.box.border?.width ?: 0f) / 2
            val x = element.x + element.box.width / 2; val y = element.top + element.box.height / 2
            floatArrayOf(x - dx - stroke, y - dy - stroke, x + dx + stroke, y + dy + stroke)
        }
        else -> error("Not a floating page-band object")
    }

    private fun unsupported(message: String): Nothing = throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, message)

    private fun overflow(): Nothing = throw PdfRenderException(PdfFailureCode.CONTENT_OVERFLOW, "Floating page-band object exceeds the physical page")

}
