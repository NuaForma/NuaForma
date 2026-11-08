package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText

/** Qualify inactive East Asian spacing without inventing inter-character advances. */
internal class WordAutomaticTextSpacing(private val context: GenerationContext) {
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val names = setOf("autoSpaceDE", "autoSpaceDN")
    private data class Switch(val part: String, val parent: XmlElement, val node: XmlElement, val enabled: Boolean)

    fun normalize(parts: Map<String, XmlElement>, fieldText: (XmlElement) -> String? = { null },
        failure: (String, XmlElement, String) -> NativeDocxReadException) {
        val switches = mutableListOf<Switch>()
        for ((part, root) in parts) for (parent in root.descendants().filter { it.name.namespaceURI == word && it.name.localPart == "pPr" }) {
            val seen = mutableSetOf<String>()
            for (node in parent.elements().filter { it.name.namespaceURI == word && it.name.localPart in names }) {
                context.checkActive()
                val value = node.attribute(word, "val")
                if (!seen.add(node.name.localPart) || node.elements().isNotEmpty() ||
                    node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() } ||
                    node.attributes.keys.any { it.namespaceURI != word || it.localPart != "val" } ||
                    value !in setOf(null, "1", "0", "true", "false", "on", "off"))
                    throw failure(part, node, "Invalid automatic text spacing switch")
                switches += Switch(part, parent, node, value !in setOf("0", "false", "off"))
            }
        }
        val enabled = switches.firstOrNull { it.enabled }
        if (enabled != null && containsEastAsianText(parts.values, fieldText))
            throw failure(enabled.part, enabled.node, "Automatic East Asian character spacing is not qualified for this text")
        for (entry in switches) {
            context.checkActive()
            entry.parent.children.remove(entry.node)
        }
    }

    private fun containsEastAsianText(roots: Collection<XmlElement>, fieldText: (XmlElement) -> String?): Boolean {
        val stories = setOf("document", "body", "hdr", "ftr", "footnotes", "endnotes")
        for (root in roots.filter { it.name.namespaceURI == word && it.name.localPart in stories }) for (node in root.descendants()) {
            context.checkActive()
            if (node.children.filterIsInstance<XmlText>().any { eastAsian(it.text) }) return true
            if (node.name.namespaceURI == word && node.name.localPart == "fldSimple" && fieldText(node)?.let(::eastAsian) == true) return true
        }
        return false
    }

    private fun eastAsian(text: String): Boolean {
        context.checkActive()
        return text.codePoints().anyMatch { code ->
            Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN ||
                code in 0x1100..0x11FF || code in 0x2E80..0xA4CF || code in 0xA960..0xA97F ||
                code in 0xAC00..0xD7FF || code in 0xF900..0xFAFF || code in 0xFE10..0xFE6F ||
                code in 0xFF00..0xFFEF || code in 0x16FE0..0x18DFF || code in 0x1AFF0..0x1B2FF || code in 0x1F200..0x1F2FF
        }
    }

}
