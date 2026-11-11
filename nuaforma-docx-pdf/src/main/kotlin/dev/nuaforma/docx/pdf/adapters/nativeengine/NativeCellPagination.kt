package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText

/** Retain original validation for Word flags omitted from qualified cell printing. */
internal object NativeCellPagination {
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val names = setOf("keepLines", "widowControl")
    private val flags = setOf(null, "0", "1", "false", "true", "off", "on")

    fun validateSource(root: XmlElement, fail: (XmlElement, String) -> Nothing) {
        for (properties in root.descendants().filter { it.name.namespaceURI == W && it.name.localPart == "pPr" })
            validateProperties(properties, fail)
    }

    fun validateProperties(properties: XmlElement, fail: (XmlElement, String) -> Nothing) {
        val declarations = properties.elements().filter { it.name.localPart in names }
        val seen = mutableSetOf<String>()
        for (node in declarations) {
            if (!seen.add(node.name.localPart)) fail(node, "Duplicate original paragraph pagination flag")
            if (node.name.namespaceURI != W || node.attributes.keys.any { it.namespaceURI != W || it.localPart != "val" } ||
                node.attribute(W, "val") !in flags || node.children.any { it !is XmlText || it.text.isNotBlank() })
                fail(node, "Invalid original paragraph pagination flag")
        }
    }
}
