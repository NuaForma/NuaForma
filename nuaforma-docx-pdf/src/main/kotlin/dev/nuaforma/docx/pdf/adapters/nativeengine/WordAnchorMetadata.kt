package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText

/** Word retains zero-valued relative-size placeholders alongside an explicit physical extent. */
internal object WordAnchorMetadata {
    private const val WP14 = "http://schemas.microsoft.com/office/word/2010/wordprocessingDrawing"

    fun validate(node: XmlElement, reject: (String) -> Nothing) {
        val horizontal = node.name.localPart == "sizeRelH"
        if (node.name.namespaceURI != WP14 || node.name.localPart !in setOf("sizeRelH", "sizeRelV"))
            reject("Unsupported anchored image child: ${node.name.localPart.take(64)}")
        val references = if (horizontal) setOf("page", "margin", "leftMargin", "rightMargin", "insideMargin", "outsideMargin")
            else setOf("page", "margin", "topMargin", "bottomMargin", "insideMargin", "outsideMargin")
        if (node.attributes.size != 1 || node.attribute("", "relativeFrom") !in references ||
            node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() }) reject("Invalid relative image size")
        val value = node.elements().singleOrNull() ?: reject("Invalid relative image size")
        if (value.name.namespaceURI != WP14 || value.name.localPart != (if (horizontal) "pctWidth" else "pctHeight") ||
            value.attributes.isNotEmpty() || value.elements().isNotEmpty()) reject("Invalid relative image size value")
        val text = value.children.filterIsInstance<XmlText>().joinToString("") { it.text }.trim()
        if (text != "0") reject("Nonzero relative image sizing is not qualified")
    }

}
