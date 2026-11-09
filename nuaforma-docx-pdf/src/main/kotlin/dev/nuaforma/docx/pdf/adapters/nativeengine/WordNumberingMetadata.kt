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
    private val paragraphNumber = Regex("[+]?[0-9]{1,7}")

    fun validateParagraphNumberingSource(root: XmlElement, failure: (XmlElement) -> NativeDocxReadException) {
        for (properties in root.descendants().filter { it.name == QName(word, "pPr") }) {
            context.checkActive()
            val declarations = properties.elements().filter { it.name.localPart == "numPr" }
            if (declarations.size > 1) throw failure(declarations[1])
            for (declaration in declarations) validateParagraphNumbering(declaration, failure)
        }
    }

    private fun validateParagraphNumbering(node: XmlElement, failure: (XmlElement) -> NativeDocxReadException) {
        numberingAttributes(node, emptySet(), failure)
        if (node.name != QName(word, "numPr") || node.elements().isEmpty() ||
            node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() }) throw failure(node)
        val seen = mutableSetOf<String>()
        for (child in node.elements()) {
            context.checkActive()
            numberingAttributes(child, setOf("val"), failure)
            val value = child.attribute(word, "val")
            if (child.name.namespaceURI != word || child.name.localPart !in setOf("numId", "ilvl") ||
                !seen.add(child.name.localPart) || child.elements().isNotEmpty() ||
                child.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() } || value == null ||
                !value.matches(paragraphNumber) || value.toIntOrNull() !in
                0..(if (child.name.localPart == "ilvl") 8 else 1_000_000)) throw failure(child)
        }
    }

    private fun numberingAttributes(node: XmlElement, allowed: Set<String>, failure: (XmlElement) -> NativeDocxReadException) {
        val unsupported = node.attributes.keys.filter { it.namespaceURI != word || it.localPart !in allowed }.toSet()
        if (unsupported.isNotEmpty()) throw failure(node).also { it.unsupportedAttributes = unsupported }
    }

    fun validateDefinitionNamesSource(root: XmlElement, failure: (XmlElement) -> NativeDocxReadException) {
        if (root.name.namespaceURI != word || root.name.localPart != "numbering") throw failure(root)
        for (definition in root.elements().filter { it.name.localPart == "abstractNum" }) {
            val names = definition.elements().filter { it.name.localPart in setOf("styleLink", "name") }
            val duplicate = names.groupBy { it.name.localPart }.values.firstOrNull { it.size > 1 }
            if (duplicate != null) throw failure(duplicate[1])
            for (node in names) {
                context.checkActive()
                val value = node.attribute(word, "val")
                if (definition.name.namespaceURI != word || node.name.namespaceURI != word ||
                    node.attributes.keys.any { it.namespaceURI != word || it.localPart != "val" } ||
                    node.elements().isNotEmpty() || node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() } ||
                    value.isNullOrBlank() || value.length > 253) throw failure(node)
            }
        }
    }

    fun normalize(root: XmlElement, failure: (XmlElement) -> NativeDocxReadException) {
        validateDefinitionNamesSource(root, failure)
        for (definition in root.elements().filter { it.name.namespaceURI == word && it.name.localPart == "abstractNum" }) {
            definition.children.removeAll(definition.elements().filter { it.name.namespaceURI == word && it.name.localPart in setOf("styleLink", "name") }.toSet())
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
