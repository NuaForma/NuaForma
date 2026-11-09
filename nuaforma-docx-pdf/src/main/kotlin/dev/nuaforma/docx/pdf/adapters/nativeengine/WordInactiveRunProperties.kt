package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText
import javax.xml.namespace.QName

/** Explicit no-op formatting is qualified before binding; active effects remain unsupported. */
internal object WordInactiveRunProperties {
    private const val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val names = setOf("dstrike", "w")

    fun validateSource(root: XmlElement, reject: (XmlElement, String) -> Nothing) {
        for (properties in root.descendants().filter { it.name.namespaceURI == word && it.name.localPart == "rPr" }) {
            for (name in names) {
                val nodes = properties.elements().filter { it.name.localPart == name }
                if (nodes.size > 1) reject(nodes[1], "Duplicate inactive run property")
                nodes.forEach { validateShape(it, reject) }
            }
        }
    }

    /** JAXB boolean binding does not preserve Word's on/off aliases without canonicalization. */
    fun normalizeSource(root: XmlElement, reject: (XmlElement, String) -> Nothing): Boolean {
        validateSource(root, reject)
        var changed = false
        for (properties in root.descendants().filter { it.name.namespaceURI == word && it.name.localPart == "rPr" }) {
            for (node in properties.elements().filter { it.name.localPart == "dstrike" }) {
                val canonical = when (node.attribute(word, "val")) { "off" -> "false"; "on" -> "true"; else -> null }
                if (canonical != null) {
                    node.attributes[QName(word, "val")] = canonical
                    changed = true
                }
            }
        }
        return changed
    }

    private fun validateShape(node: XmlElement, reject: (XmlElement, String) -> Nothing) {
        if (node.name.namespaceURI != word || node.name.localPart !in names ||
            node.attributes.keys.any { it.namespaceURI != word || it.localPart != "val" } ||
            node.elements().isNotEmpty() || node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() })
            reject(node, "Invalid inactive run property structure")
        val raw = node.attribute(word, "val")
        if (node.name.localPart == "dstrike") {
            if (raw != null && raw !in setOf("0", "false", "off", "1", "true", "on"))
                reject(node, "Invalid original double-strike toggle")
        } else if (raw == null || !raw.matches(Regex("[0-9]{1,6}"))) reject(node, "Invalid original horizontal text scale")
    }

    fun requireInactive(node: XmlElement, reject: (XmlElement, String) -> Nothing) {
        validateShape(node, reject)
        when (node.name.localPart) {
            "dstrike" -> if (node.attribute(word, "val") !in setOf("0", "false", "off"))
                reject(node, "Active double-strike is not qualified")
            "w" -> if (node.attribute(word, "val") != "100")
                reject(node, "Non-default horizontal text scaling is not qualified")
        }
    }

}
