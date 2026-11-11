package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText

/** Original row-height syntax is checked before binding; active rows use Word's minimum default. */
internal object WordRowHeights {
    private const val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    fun validateSource(root: XmlElement, reject: (XmlElement, String) -> Nothing) {
        for (parent in root.descendants()) {
            val heights = parent.elements().filter { it.name.localPart == "trHeight" }
            if (heights.isEmpty()) continue
            if (parent.name.namespaceURI != word || parent.name.localPart != "trPr") reject(heights.first(), "Misplaced row height")
            if (heights.size > 1) reject(heights[1], "Duplicate row height")
            heights.forEach { measurement(it, reject) }
        }
    }

    private fun measurement(node: XmlElement, reject: (XmlElement, String) -> Nothing): Int {
        if (node.name.namespaceURI != word || node.attributes.keys.any { it.namespaceURI != word || it.localPart !in setOf("val", "hRule") } ||
            node.elements().isNotEmpty() || node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() })
            reject(node, "Unsupported original row height structure")
        if (node.attribute(word, "hRule") !in setOf(null, "auto", "atLeast", "exact")) reject(node, "Invalid row height rule")
        val raw = node.attribute(word, "val") ?: reject(node, "Missing row height measurement")
        if (!raw.matches(Regex("[0-9]{1,6}"))) reject(node, "Invalid row height measurement")
        return raw.toInt().also { if (it > 288_000) reject(node, "Row height exceeds the layout limit") }
    }

    fun read(node: XmlElement, reject: (XmlElement, String) -> Nothing): Float? {
        val value = measurement(node, reject)
        return when (node.attribute(word, "hRule")) {
            null, "atLeast" -> value.takeIf { it > 0 }?.div(20f)
            "auto" -> {
                if (value != 0) reject(node, "Automatic row height requires zero measurement")
                null
            }
            else -> reject(node, "Exact row heights are outside the qualified profile")
        }
    }

}
