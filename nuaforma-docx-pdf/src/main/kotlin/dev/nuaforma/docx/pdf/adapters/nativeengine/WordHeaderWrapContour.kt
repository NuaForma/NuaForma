package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText

/** Only the contour's physical lower edge is needed to prove it cannot alter body flow. */
internal object WordHeaderWrapContour {
    private const val wp = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"

    fun bottom(wrap: XmlElement, reject: (XmlElement, String) -> Nothing): Float {
        if (wrap.attributes.size != 1 || wrap.attribute("", "wrapText") != "bothSides" || hasText(wrap))
            reject(wrap, "Unsupported tight header image wrapping properties")
        val contour = wrap.elements().singleOrNull()
        if (contour == null || contour.name.namespaceURI != wp || contour.name.localPart != "wrapPolygon" ||
            contour.attributes.keys.any { it.namespaceURI.isNotEmpty() || it.localPart != "edited" } ||
            contour.attribute("", "edited") !in setOf(null, "0", "false", "1", "true") || hasText(contour))
            reject(contour ?: wrap, "Invalid tight header image contour")
        val points = contour.elements()
        if (points.size !in 4..128) reject(contour, "Invalid tight header image contour point count")
        val vertices = points.mapIndexed { index, node ->
            if (node.name.namespaceURI != wp || node.name.localPart != (if (index == 0) "start" else "lineTo") ||
                node.attributes.size != 2 || node.elements().isNotEmpty() || hasText(node))
                reject(node, "Invalid tight header image contour vertex")
            fun coordinate(axis: String): Int =
                node.attribute("", axis)?.toIntOrNull()?.takeIf { it in 0..21_600 }
                    ?: reject(node, "Tight header image contour vertex exceeds the image extent")
            coordinate("x") to coordinate("y")
        }
        if (vertices.first() != vertices.last() || vertices.distinct().size < 3)
            reject(contour, "Tight header image contour must be a closed polygon")
        return vertices.maxOf { it.second } / 21_600f
    }

    private fun hasText(node: XmlElement): Boolean = node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() }
}
