package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText
import javax.xml.namespace.QName

/** List-gallery identifiers and explicitly disabled section restarts do not change printed numbering. */
internal class WordNumberingMetadata(private val context: GenerationContext) {
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val word15 = "http://schemas.microsoft.com/office/word/2012/wordml"
    private val durable = "http://schemas.microsoft.com/office/word/2016/wordml/cid"

    fun validateStyleLinksSource(root: XmlElement, failure: (XmlElement) -> NativeDocxReadException) {
        if (root.name.namespaceURI != word || root.name.localPart != "numbering") throw failure(root)
        for (definition in root.elements().filter { it.name.localPart == "abstractNum" }) {
            val links = definition.elements().filter { it.name.localPart == "styleLink" }
            if (links.size > 1) throw failure(links[1])
            for (link in links) {
                context.checkActive()
                val value = link.attribute(word, "val")
                if (definition.name.namespaceURI != word || link.name.namespaceURI != word ||
                    link.attributes.keys.any { it.namespaceURI != word || it.localPart != "val" } ||
                    link.elements().isNotEmpty() || link.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() } ||
                    value.isNullOrBlank() || value.length > 253) throw failure(link)
            }
        }
    }

    fun normalize(root: XmlElement, failure: (XmlElement) -> NativeDocxReadException) {
        validateStyleLinksSource(root, failure)
        for (definition in root.elements().filter { it.name.namespaceURI == word && it.name.localPart == "abstractNum" }) {
            definition.children.removeAll(definition.elements().filter { it.name.namespaceURI == word && it.name.localPart == "styleLink" }.toSet())
        }
        val cleanup = root.elements().filter { it.name.namespaceURI == word && it.name.localPart == "numIdMacAtCleanup" }
        if (cleanup.size > 1) throw failure(cleanup[1])
        for (node in cleanup) {
            context.checkActive()
            val value = node.attribute(word, "val")
            if (node.elements().isNotEmpty() || node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() } ||
                node.attributes.keys.any { it.namespaceURI != word || it.localPart != "val" } ||
                value == null || !value.matches(Regex("[+]?[0-9]{1,10}")) || value.toLongOrNull() !in 0L..2_147_483_647L) throw failure(node)
        }
        root.children.removeAll(cleanup.toSet())
        for (node in root.descendants()) {
            context.checkActive()
            if (node.name.namespaceURI != word) continue
            for ((key, value) in node.attributes.toMap()) {
                val inert = when {
                    node.name.localPart == "lvl" && key.namespaceURI == word && key.localPart == "tplc" -> value.matches(Regex("[A-Fa-f0-9]{8}"))
                    node.name.localPart == "lvl" && key.namespaceURI == word && key.localPart == "tentative" -> value in setOf("true", "false", "1", "0", "on", "off")
                    node.name.localPart == "num" && key.namespaceURI == durable && key.localPart == "durableId" -> value.toIntOrNull()?.let { it in 1 until Int.MAX_VALUE } == true
                    node.name.localPart == "abstractNum" && key.namespaceURI == word15 && key.localPart == "restartNumberingAfterBreak" -> value in setOf("false", "0", "off")
                    else -> continue
                }
                if (!inert) throw failure(node)
                node.attributes.remove(key)
            }
        }
    }
}
