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
    private val signedDecimal = Regex("-?[0-9]{1,6}(?:\\.[0-9]{1,18})?")

    fun normalizeSource(root: XmlElement, reject: (XmlElement, String) -> Nothing): Boolean {
        val emptyLineSpacings = WordZeroHeightParagraphs.sourceSpacings(root)
        var changed = false
        for (parent in root.descendants().filter { it.name.namespaceURI == word }) {
            changed = when (parent.name.localPart) {
                "tblGrid" -> grid(parent, reject)
                "tblPr" -> tableProperties(parent, reject)
                "pPr" -> {
                    validateNegativeTabs(parent, reject)
                    paragraphSpacing(parent, emptyLineSpacings, reject)
                }
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
        if (!raw.matches(if (minimum < 0) signedDecimal else decimal)) reject(node, "Invalid original Word numeric measurement")
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

    fun tableIndentPoints(node: XmlElement, reject: (XmlElement, String) -> Nothing): Float {
        leaf(node, setOf("w", "type"), reject)
        if (node.attribute(word, "type") != "dxa") reject(node, "Only physical table indentation is qualified")
        integer(node, "w", -288_000, reject)
        return requireNotNull(node.attribute(word, "w")).toInt() / 20f
    }

    private fun tableProperties(parent: XmlElement, reject: (XmlElement, String) -> Nothing): Boolean {
        val nodes = parent.elements().filter { it.name.localPart == "tblInd" }
        if (nodes.size > 1) reject(nodes[1], "Duplicate original table indentation")
        var changed = false
        for (node in nodes) {
            leaf(node, setOf("w", "type"), reject)
            if (node.attribute(word, "type") != "dxa") reject(node, "Only physical table indentation is qualified")
            changed = integer(node, "w", -288_000, reject) || changed
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

    private fun validateNegativeTabs(parent: XmlElement, reject: (XmlElement, String) -> Nothing) {
        for (tabs in parent.elements().filter { it.name == QName(word, "tabs") }) {
            for (tab in tabs.elements().filter { it.attribute(word, "pos")?.startsWith('-') == true }) {
                leaf(tab, setOf("val", "pos", "leader"), reject)
                val position = tab.attribute(word, "pos")?.toIntOrNull()
                if (tab.name.localPart != "tab" || position == null || position < -288_000)
                    reject(tab, "Invalid original negative tab position")
            }
        }
    }

    fun spacingInLineUnits(node: XmlElement, value: String, reject: (XmlElement, String) -> Nothing): Float {
        val units = value.takeIf { it.matches(Regex("[0-9]{1,6}")) }?.toInt()
            ?: reject(node, "Invalid Word paragraph spacing in line units")
        if (units > 120_000) reject(node, "Word paragraph spacing in line units exceeds the qualified bounds")
        // Word uses a nominal 12-point line here, independent of the line height of the adjacent text.
        return units * 12f / 100f
    }

    private fun paragraphSpacing(parent: XmlElement, emptyLineSpacings: Set<XmlElement>, reject: (XmlElement, String) -> Nothing): Boolean {
        val nodes = parent.elements().filter { it.name.localPart == "spacing" }
        val measurements = listOf("line", "beforeLines", "afterLines")
        if (nodes.none { node -> measurements.any { node.attribute(word, it) != null } }) return false
        if (nodes.size > 1) reject(nodes[1], "Duplicate original paragraph spacing")
        val node = nodes.single()
        leaf(node, setOf("before", "after", "line", "lineRule", "beforeAutospacing", "afterAutospacing", "beforeLines", "afterLines"), reject)
        for (name in listOf("beforeLines", "afterLines")) node.attribute(word, name)?.let { spacingInLineUnits(node, it, reject) }
        if (node.attribute(word, "line") == null) return false
        if (node.attribute(word, "lineRule") !in setOf(null, "auto")) {
            if (node.attribute(word, "line")?.contains('.') == true) reject(node, "Fractional non-automatic line spacing is not qualified")
            return false
        }
        if (node in emptyLineSpacings) return false
        return integer(node, "line", 1, reject)
    }

}
