package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText

/** A paragraph mark's highlight is editing formatting, not a background for its visible runs. */
internal object WordParagraphMark {
    private const val WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val colors = setOf("none", "black", "blue", "cyan", "green", "magenta", "red", "yellow", "white",
        "darkBlue", "darkCyan", "darkGreen", "darkMagenta", "darkRed", "darkYellow", "darkGray", "lightGray")

    fun metricProperties(property: XmlElement, validate: (XmlElement, Boolean) -> Unit): XmlElement {
        val highlights = property.elements().filter { it.name.namespaceURI == WORD && it.name.localPart == "highlight" }
        if (highlights.isEmpty()) return property
        for (node in highlights) validate(node, highlights.size == 1 && node.attributes.size == 1 &&
            node.attribute(WORD, "val") in colors && node.elements().isEmpty() &&
            node.children.filterIsInstance<XmlText>().all { it.text.isBlank() })
        return XmlElement(property.name, property.attributes, property.namespaces).also { copy ->
            copy.children += property.children.filterNot { it in highlights }
        }
    }

}
