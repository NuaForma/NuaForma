package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText

/** Relative-size limits are percentages of a physical page or margin rectangle. */
internal object WordAnchorMetadata {
    private const val WP14 = "http://schemas.microsoft.com/office/word/2010/wordprocessingDrawing"

    data class RelativeSize(val horizontal: Boolean, val reference: String, val fraction: Double)

    fun read(node: XmlElement, reject: (String) -> Nothing): RelativeSize? {
        val horizontal = node.name.localPart == "sizeRelH"
        if (node.name.namespaceURI != WP14 || node.name.localPart !in setOf("sizeRelH", "sizeRelV"))
            reject("Unsupported anchored image child: ${node.name.localPart.take(64)}")
        val references = if (horizontal) setOf("page", "margin", "leftMargin", "rightMargin", "insideMargin", "outsideMargin")
            else setOf("page", "margin", "topMargin", "bottomMargin", "insideMargin", "outsideMargin")
        val reference = node.attribute("", "relativeFrom") ?: reject("Missing relative image sizing reference")
        if (node.attributes.size != 1 || reference !in references ||
            node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() }) reject("Invalid relative image size")
        val value = node.elements().singleOrNull() ?: reject("Invalid relative image size")
        if (value.name.namespaceURI != WP14 || value.name.localPart != (if (horizontal) "pctWidth" else "pctHeight") ||
            value.attributes.isNotEmpty() || value.elements().isNotEmpty()) reject("Invalid relative image size value")
        val text = value.children.filterIsInstance<XmlText>().joinToString("") { it.text }.trim()
        val amount = text.toIntOrNull()?.takeIf { it in 0..100_000 } ?: reject("Invalid relative image size percentage")
        if (amount == 0) return null
        if (reference !in setOf("page", "margin")) reject("Relative image sizing requires a page or margin reference")
        return RelativeSize(horizontal, reference, amount / 100_000.0)
    }

}
