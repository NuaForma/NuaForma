package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText

/** Qualify inactive spacing and baseline alignment without inventing script-specific metrics. */
internal class WordAutomaticTextSpacing(private val context: GenerationContext) {
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val names = setOf("autoSpaceDE", "autoSpaceDN", "textAlignment")
    private data class Switch(val part: String, val parent: XmlElement, val node: XmlElement, val enabled: Boolean)

    fun normalize(parts: Map<String, XmlElement>, fieldText: (XmlElement) -> String? = { null },
        failure: (String, XmlElement, String) -> NativeDocxReadException) {
        val switches = mutableListOf<Switch>()
        for ((part, root) in parts) for (parent in root.descendants().filter { it.name.namespaceURI == word && it.name.localPart == "pPr" }) {
            val seen = mutableSetOf<String>()
            for (node in parent.elements().filter { it.name.namespaceURI == word && it.name.localPart in names }) {
                context.checkActive()
                val value = node.attribute(word, "val")
                val alignment = node.name.localPart == "textAlignment"
                val values = if (alignment) setOf("auto", "baseline") else setOf(null, "1", "0", "true", "false", "on", "off")
                if (!seen.add(node.name.localPart) || node.elements().isNotEmpty() ||
                    node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() } ||
                    node.attributes.keys.any { it.namespaceURI != word || it.localPart != "val" } ||
                    value !in values)
                    throw failure(part, node, if (alignment) "Unsupported paragraph text alignment" else "Invalid automatic text spacing switch")
                switches += Switch(part, parent, node, if (alignment) value == "auto" else value !in setOf("0", "false", "off"))
            }
        }
        val enabled = switches.firstOrNull { it.enabled && it.node.name.localPart != "textAlignment" }
        if (enabled != null && containsText(parts.values, fieldText, ::eastAsian))
            throw failure(enabled.part, enabled.node, "Automatic East Asian character spacing is not qualified for this text")
        val alignment = switches.firstOrNull { it.enabled && it.node.name.localPart == "textAlignment" }
        if (alignment != null && (WordAuthoringMetadata.containsComplexText(parts.values) ||
                containsText(parts.values, fieldText) { eastAsian(it) || complexScript(it) }))
            throw failure(alignment.part, alignment.node, "Automatic paragraph text alignment is not qualified for this text")
        for (entry in switches) {
            context.checkActive()
            entry.parent.children.remove(entry.node)
        }
    }

    private fun containsText(roots: Collection<XmlElement>, fieldText: (XmlElement) -> String?, matches: (String) -> Boolean): Boolean {
        val stories = setOf("document", "body", "hdr", "ftr", "footnotes", "endnotes")
        for (root in roots.filter { it.name.namespaceURI == word && it.name.localPart in stories }) for (node in root.descendants()) {
            context.checkActive()
            if (node.children.filterIsInstance<XmlText>().any { matches(it.text) }) return true
            if (node.name.namespaceURI == word && node.name.localPart == "fldSimple" && fieldText(node)?.let(matches) == true) return true
        }
        return false
    }

    private fun complexScript(text: String): Boolean {
        context.checkActive()
        return text.codePoints().anyMatch { code -> Character.isLetter(code) && Character.UnicodeScript.of(code) !in
            setOf(Character.UnicodeScript.LATIN, Character.UnicodeScript.GREEK, Character.UnicodeScript.CYRILLIC) }
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
