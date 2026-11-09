package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText
import java.math.BigDecimal
import javax.xml.namespace.QName

/** Word truncates qualified decimal spellings before interpreting these integer layout fields. */
internal object WordNumericSpellings {
    private const val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val sides = setOf("top", "right", "bottom", "left")
    private val decimal = Regex("[0-9]{1,6}(?:\\.[0-9]{1,18})?")

    fun normalizeSource(root: XmlElement, reject: (XmlElement, String) -> Nothing): Boolean {
        var changed = false
        for (parent in root.descendants().filter { it.name.namespaceURI == word }) {
            changed = when (parent.name.localPart) {
                "tblGrid" -> grid(parent, reject)
                "tblPr" -> tableProperties(parent, reject)
                "pPr" -> automaticSpacing(parent, reject)
                else -> false
            } || changed
        }
        return changed
    }

    private fun leaf(node: XmlElement, names: Set<String>, reject: (XmlElement, String) -> Nothing) {
        if (node.name.namespaceURI != word || node.elements().isNotEmpty() ||
            node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() })
            reject(node, "Invalid original Word numeric property structure")
        val unsupported = node.attributes.keys.filter { it.namespaceURI != word || it.localPart !in names }.toSet()
        if (unsupported.isNotEmpty()) rejectAttributes(node, unsupported, reject)
    }

    private fun rejectAttributes(node: XmlElement, unsupported: Set<QName>, reject: (XmlElement, String) -> Nothing): Nothing {
        try { reject(node, "Unsupported original Word numeric attribute") }
        catch (error: NativeDocxReadException) {
            error.unsupportedAttributes = unsupported
            throw error
        }
    }

    private fun integer(node: XmlElement, name: String, minimum: Int, reject: (XmlElement, String) -> Nothing): Boolean {
        val raw = node.attribute(word, name) ?: reject(node, "Missing original Word numeric measurement")
        if (!raw.matches(decimal)) reject(node, "Invalid original Word numeric measurement")
        val value = raw.toBigDecimal()
        if (value < BigDecimal(minimum) || value > BigDecimal(288_000)) reject(node, "Word numeric measurement exceeds the qualified bounds")
        val canonical = value.toInt().toString()
        if (canonical == raw) return false
        node.attributes[QName(word, name)] = canonical
        return true
    }

    private fun grid(parent: XmlElement, reject: (XmlElement, String) -> Nothing): Boolean {
        var changed = false
        for (node in parent.elements()) {
            if (node.name != QName(word, "gridCol")) reject(node, "Unsupported original table-grid construct")
            leaf(node, setOf("w"), reject)
            changed = integer(node, "w", 1, reject) || changed
        }
        return changed
    }

    private fun tableProperties(parent: XmlElement, reject: (XmlElement, String) -> Nothing): Boolean {
        val nodes = parent.elements().filter { it.name.localPart == "tblInd" }
        if (nodes.size > 1) reject(nodes[1], "Duplicate original table indentation")
        var changed = false
        for (node in nodes) {
            leaf(node, setOf("w", "type"), reject)
            if (node.attribute(word, "type") != "dxa") reject(node, "Only physical table indentation is qualified")
            changed = integer(node, "w", 0, reject) || changed
        }
        return tableMargins(parent, reject) || changed
    }

    private fun tableMargins(parent: XmlElement, reject: (XmlElement, String) -> Nothing): Boolean {
        val nodes = parent.elements().filter { it.name.localPart == "tblCellMar" }
        if (nodes.size > 1) reject(nodes[1], "Duplicate original table-default cell margins")
        var changed = false
        for (margins in nodes) {
            if (margins.name.namespaceURI != word || margins.attributes.isNotEmpty() ||
                margins.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() }) reject(margins, "Invalid original table-default margin structure")
            val seen = mutableSetOf<String>()
            for (edge in margins.elements()) {
                if (edge.name.localPart !in sides || !seen.add(edge.name.localPart)) reject(edge, "Unsupported or duplicate table-default margin edge")
                leaf(edge, setOf("w", "type"), reject)
                if (edge.attribute(word, "type") != "dxa") reject(edge, "Only physical table-default margins are qualified")
                changed = integer(edge, "w", 0, reject) || changed
            }
        }
        return changed
    }

    private fun automaticSpacing(parent: XmlElement, reject: (XmlElement, String) -> Nothing): Boolean {
        val nodes = parent.elements().filter { it.name.localPart == "spacing" }
        if (nodes.none { it.attribute(word, "line") != null }) return false
        if (nodes.size > 1) reject(nodes[1], "Duplicate original paragraph spacing")
        val node = nodes.single()
        leaf(node, setOf("before", "after", "line", "lineRule", "beforeAutospacing", "afterAutospacing"), reject)
        if (node.attribute(word, "lineRule") !in setOf(null, "auto")) {
            if (node.attribute(word, "line")?.contains('.') == true) reject(node, "Fractional non-automatic line spacing is not qualified")
            return false
        }
        return integer(node, "line", 1, reject)
    }

}
