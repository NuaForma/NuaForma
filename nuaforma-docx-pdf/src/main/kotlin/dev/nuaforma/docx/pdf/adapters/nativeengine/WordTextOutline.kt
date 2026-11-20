package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText
import javax.xml.namespace.QName

/** Qualified width-zero Word text outlines contribute no outline paint. */
internal object WordTextOutline {
    const val NS = "http://schemas.microsoft.com/office/word/2010/wordml"
    private const val WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val color = Regex("[0-9A-Fa-f]{6}")

    fun validateSource(root: XmlElement, reject: (XmlElement, String) -> Nothing) {
        for (properties in root.descendants().filter { it.name == QName(WORD, "rPr") }) {
            val outlines = properties.elements().filter { it.name.localPart == "textOutline" }
            if (outlines.size > 1) reject(outlines[1], "Duplicate original Word text outline")
            // Validate accepted original zero-width structures before binding.
            // The effective run check still rejects visible outlines when their formatting is used.
            outlines.singleOrNull()?.takeIf { it.attribute(NS, "w") == "0" }?.let { requireInactive(it, reject) }
        }
    }

    fun requireInactive(node: XmlElement, reject: (XmlElement, String) -> Nothing) {
        shape(node, "textOutline", setOf("w", "cap", "cmpd", "algn"), reject)
        if (node.attribute(NS, "w") != "0")
            reject(node, "Only explicitly zero-width Word text outlines are qualified")
        if (node.attribute(NS, "cap") !in setOf(null, "rnd") || node.attribute(NS, "cmpd") !in setOf(null, "sng") ||
            node.attribute(NS, "algn") !in setOf(null, "ctr")) reject(node, "Unsupported Word text outline geometry")
        val children = node.elements()
        if (children.isEmpty()) return
        if (children.size != 3) reject(node, "Unsupported zero-width Word text outline structure")
        val (fill, dash, join) = children
        when (fill.name.localPart) {
            "noFill" -> leaf(fill, "noFill", emptySet(), reject)
            "solidFill" -> {
                shape(fill, "solidFill", emptySet(), reject)
                val component = fill.elements().singleOrNull() ?: reject(fill, "Explicit outline RGB color required")
                leaf(component, "srgbClr", setOf("val"), reject)
                if (component.attribute(NS, "val")?.matches(color) != true) reject(component, "Invalid outline RGB color")
            }
            else -> reject(fill, "Unsupported zero-width Word text outline fill")
        }
        leaf(dash, "prstDash", setOf("val"), reject)
        if (dash.attribute(NS, "val") != "solid") reject(dash, "Only solid zero-width text outlines are qualified")
        leaf(join, "bevel", emptySet(), reject)
    }

    private fun leaf(node: XmlElement, name: String, attributes: Set<String>, reject: (XmlElement, String) -> Nothing) {
        shape(node, name, attributes, reject)
        if (node.elements().isNotEmpty()) reject(node, "Unexpected content in Word text outline")
    }

    private fun shape(node: XmlElement, name: String, attributes: Set<String>, reject: (XmlElement, String) -> Nothing) {
        if (node.name != QName(NS, name) || node.attributes.keys.any { it.namespaceURI != NS || it.localPart !in attributes } ||
            node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() }) reject(node, "Invalid Word text outline structure")
    }

}
