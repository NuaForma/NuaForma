package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText
import dev.nuaforma.pdf.PdfMargins
import java.math.BigDecimal
import javax.xml.namespace.QName

/** Resolve physical cell edges only after checking the original package representation. */
internal object WordCellMargins {
    private const val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val sides = setOf("top", "right", "bottom", "left")

    fun validateSource(root: XmlElement, reject: (XmlElement, String) -> Nothing) {
        for (properties in root.descendants().filter { it.name.namespaceURI == word && it.name.localPart == "tcPr" }) {
            val margins = properties.elements().filter { it.name.localPart == "tcMar" }
            if (margins.size > 1) reject(margins[1], "Duplicate cell margins")
            margins.forEach { resolve(listOf(it), PdfMargins(0f, 0f, 0f, 0f), reject) }
        }
    }

    /** Word truncates fractional twips; canonicalize them before integer-only binding. */
    fun normalizeSource(root: XmlElement, reject: (XmlElement, String) -> Nothing): Boolean {
        validateSource(root, reject)
        var changed = false
        for (properties in root.descendants().filter { it.name.namespaceURI == word && it.name.localPart == "tcPr" }) {
            for (node in properties.elements().filter { it.name.localPart == "tcMar" }) for (edge in node.elements()) {
                if (edge.attribute(word, "type") == "nil") {
                    // Preserve the table-default reset through binding; removing it would expose a cell-style override.
                    continue
                }
                val raw = requireNotNull(edge.attribute(word, "w"))
                val canonical = raw.toBigDecimal().toInt().toString()
                if (raw != canonical) {
                    edge.attributes[QName(word, "w")] = canonical
                    changed = true
                }
            }
        }
        return changed
    }

    fun resolve(nodes: List<XmlElement>, inherited: PdfMargins, reject: (XmlElement, String) -> Nothing): PdfMargins {
        var value = inherited
        for (node in nodes) {
            if (node.name.namespaceURI != word || node.attributes.isNotEmpty() ||
                node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() }) reject(node, "Invalid cell margin structure")
            val seen = mutableSetOf<String>()
            for (edge in node.elements()) {
                val side = edge.name.localPart
                if (edge.name.namespaceURI != word || side !in sides || !seen.add(side) ||
                    edge.attributes.keys.any { it.namespaceURI != word || it.localPart !in setOf("w", "type") } ||
                    edge.elements().isNotEmpty() || edge.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() })
                    reject(edge, "Unsupported or duplicate cell margin edge")
                val type = edge.attribute(word, "type")
                if (type !in setOf("dxa", "nil")) reject(edge, "Only physical or inherited nil cell margins are qualified")
                val raw = edge.attribute(word, "w") ?: reject(edge, "Missing cell margin measurement")
                if (type == "nil") {
                    if (raw != "0") reject(edge, "Only zero-valued nil cell margins are qualified")
                    value = when (side) {
                        "top" -> value.copy(top = inherited.top)
                        "right" -> value.copy(right = inherited.right)
                        "bottom" -> value.copy(bottom = inherited.bottom)
                        else -> value.copy(left = inherited.left)
                    }
                    continue
                }
                if (!raw.matches(Regex("[0-9]{1,6}(?:\\.[0-9]{1,6})?"))) reject(edge, "Invalid cell margin measurement")
                val twips = raw.toBigDecimal()
                if (twips > BigDecimal(288_000)) reject(edge, "Cell margin exceeds the layout limit")
                val points = twips.toInt() / 20f
                value = when (side) {
                    "top" -> value.copy(top = points)
                    "right" -> value.copy(right = points)
                    "bottom" -> value.copy(bottom = points)
                    else -> value.copy(left = points)
                }
            }
        }
        return value
    }

}
