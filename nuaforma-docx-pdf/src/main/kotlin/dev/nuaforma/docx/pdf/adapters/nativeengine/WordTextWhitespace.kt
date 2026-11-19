package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement

/** Word trims XML edge whitespace unless the text node explicitly preserves it. */
internal object WordTextWhitespace {
    private const val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val xml = "http://www.w3.org/XML/1998/namespace"

    fun validateSource(root: XmlElement, context: GenerationContext, reject: (XmlElement, String) -> Nothing) {
        for (node in root.descendants()) {
            context.checkActive()
            if (node.name.namespaceURI != word || node.name.localPart != "t") continue
            if (node.attributes.keys.any { it.namespaceURI != xml || it.localPart != "space" } ||
                node.attribute(xml, "space") !in setOf(null, "default", "preserve") || node.elements().isNotEmpty())
                reject(node, "Invalid original Word text whitespace policy")
        }
    }

    fun visible(node: XmlElement, text: String): String = if (node.attribute(xml, "space") == "preserve") text else text.trim(' ', '\t', '\r', '\n')

}
