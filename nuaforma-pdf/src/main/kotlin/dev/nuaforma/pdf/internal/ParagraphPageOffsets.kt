package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.PdfPageSide
import dev.nuaforma.pdf.PdfParagraphPageOffset

/** Preserve local translation through row fragments/frames; resolve only on the final physical page. */
internal object ParagraphPageOffsets {

    fun mark(element: PositionedElement, offset: PdfParagraphPageOffset?): PositionedElement = when (element) {
        is PositionedVector -> element.copy(pageOffset = offset)
        is PositionedText -> element.copy(pageOffset = offset)
        is PositionedImage -> element.copy(pageOffset = offset)
        is PositionedStroke -> element.copy(pageOffset = if (element.pageX) null else offset)
        else -> element
    }

    fun resolve(layout: PageLayout, physicalNumber: Int): PageLayout {
        val side = if (physicalNumber % 2 == 0) PdfPageSide.EVEN else PdfPageSide.ODD

        fun apply(element: PositionedElement): PositionedElement {
            val policy = when (element) {
                is PositionedVector -> element.pageOffset
                is PositionedText -> element.pageOffset
                is PositionedImage -> element.pageOffset
                is PositionedStroke -> element.pageOffset
                else -> null
            }
            val shift = policy?.takeIf { it.side == side }?.x ?: 0f
            return when (element) {
                is PositionedVector -> element.copy(x = element.x + shift, pageOffset = null)
                is PositionedText -> element.copy(x = element.x + shift, pageOffset = null)
                is PositionedImage -> element.copy(x = element.x + shift, pageOffset = null)
                is PositionedStroke -> element.copy(x = element.x + shift, endX = element.endX + shift, pageOffset = null)
                is PositionedBox -> element.copy(elements = element.elements.map(::apply))
                else -> element
            }
        }

        return layout.copy(elements = layout.elements.map(::apply))
    }

}
