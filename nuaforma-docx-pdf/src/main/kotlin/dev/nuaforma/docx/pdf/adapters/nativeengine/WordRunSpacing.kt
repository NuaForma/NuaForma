package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText

/** Original run tracking is checked before binding; paragraph line spacing has a separate policy. */
internal object WordRunSpacing {
    private const val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    fun validateSource(root: XmlElement, reject: (XmlElement, String) -> Nothing) {
        for (properties in root.descendants().filter { it.name.namespaceURI == word && it.name.localPart == "rPr" }) {
            val spacing = properties.elements().filter { it.name.localPart == "spacing" }
            if (spacing.size > 1) reject(spacing[1], "Duplicate run character spacing")
            spacing.forEach { read(it, reject) }
        }
    }

    fun read(node: XmlElement, reject: (XmlElement, String) -> Nothing): Float {
        if (node.name.namespaceURI != word || node.attributes.keys.any { it.namespaceURI != word || it.localPart != "val" } ||
            node.elements().isNotEmpty() || node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() })
            reject(node, "Invalid original character spacing structure")
        val raw = node.attribute(word, "val") ?: reject(node, "Missing character spacing measurement")
        if (!raw.matches(Regex("[+-]?[0-9]{1,6}"))) reject(node, "Invalid character spacing measurement")
        val twips = raw.toInt()
        if (twips !in -288_000..288_000) reject(node, "Character spacing exceeds the layout limit")
        return twips / 20f
    }

}
