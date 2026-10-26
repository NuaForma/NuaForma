package dev.nuaforma.pdf.adapters.pdfbox

import dev.nuaforma.pdf.*
import dev.nuaforma.pdf.internal.PositionedText
import dev.nuaforma.pdf.internal.TextMetrics
import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSInteger
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary
import org.apache.pdfbox.pdmodel.PDDestinationNameTreeNode
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem

/** Per-render navigation state, populated from final physical placements after pagination. */
internal class PdfNavigationWriter(private val pdf: PDDocument) {
    private val destinations = sortedMapOf<String, PDPageXYZDestination>()
    private val outline = PDDocumentOutline()
    private var hasOutline = false
    private var annotations = 0

    fun add(text: PositionedText, page: PDPage, metrics: TextMetrics) {
        val navigation = text.navigation ?: return
        val height = page.mediaBox.height
        val bottom = maxOf(0f, height - text.baseline - metrics.descent)
        val top = minOf(height, height - text.baseline + metrics.ascent)
        if (text.text.isNotBlank()) for (destination in navigation.destinations) {
            if (destination.name in destinations) continue
            val target = PDPageXYZDestination().apply {
                this.page = page
                left = kotlin.math.floor(text.x.coerceIn(0f, page.mediaBox.width)).toInt()
                this.top = kotlin.math.ceil(top).toInt()
                zoom = 0f
            }
            destinations[destination.name] = target
            destination.title?.let { title ->
                outline.addLast(PDOutlineItem().apply { this.title = title; this.destination = target })
                hasOutline = true
            }
        }
        val link = navigation.link ?: return
        if (++annotations > 4_000) throw PdfRenderException(PdfFailureCode.RESOURCE_LIMIT, "Link annotation budget exceeded")
        val left = maxOf(0f, text.x)
        val right = minOf(page.mediaBox.width, text.x + metrics.width)
        if (right <= left || top <= bottom) return
        page.annotations.add(PDAnnotationLink().apply {
            rectangle = PDRectangle(left, bottom, right - left, top - bottom)
            border = COSArray().apply { repeat(3) { add(COSInteger.ZERO) } }
            isPrinted = true
            contents = text.text.take(256)
            this.page = page
            when (link) {
                is PdfLink.External -> action = PDActionURI().apply { uri = link.encodedUri }
                is PdfLink.Internal -> destination = PDNamedDestination(link.destination)
            }
        })
    }

    fun finish() {
        if (destinations.isNotEmpty()) {
            pdf.documentCatalog.names = PDDocumentNameDictionary(pdf.documentCatalog).apply {
                dests = PDDestinationNameTreeNode().apply { names = destinations }
            }
        }
        if (hasOutline) pdf.documentCatalog.documentOutline = outline
    }
}
