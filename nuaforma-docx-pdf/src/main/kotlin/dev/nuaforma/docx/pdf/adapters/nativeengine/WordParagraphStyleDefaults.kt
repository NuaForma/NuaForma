package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import javax.xml.namespace.QName

/** Qualified Word fallback uses the built-in display name, independent of style order or ID. */
internal class WordParagraphStyleDefaults(private val context: GenerationContext) {
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val enabled = setOf("1", "true", "on")
    private val flags = enabled + setOf("0", "false", "off")
    private val trimmedNameSpaces = setOf(' ', '\t', '\u00a0', '\u3000')
    private val literalNameSpaces = setOf('\u2002', '\u202f')

    fun validateSource(root: XmlElement, failure: (XmlElement, String) -> NativeDocxReadException) {
        if (root.name != QName(word, "styles")) return
        val styles = root.elements().filter { it.name == QName(word, "style") }
        for (style in styles) {
            context.checkActive()
            val invalid = style.attributes.filter { (name, value) ->
                name.localPart == "default" && (name.namespaceURI != word || value !in flags)
            }.keys
            if (invalid.isNotEmpty()) throw failure(style, "Invalid original style default attribute")
                .also { it.unsupportedAttributes = invalid.toSet() }
        }
        select(styles) { node, message -> throw failure(node, message) }
    }

    fun comparableName(value: String): String =
        if (value.trim { it in trimmedNameSpaces }.equals("Normal", ignoreCase = true)) "Normal" else value

    private fun normalName(node: XmlElement, reject: (XmlElement, String) -> Nothing): Boolean {
        val value = node.attribute(word, "val") ?: return false
        if (!value.trim().equals("Normal", ignoreCase = true)) return false
        val edges = value.takeWhile { it.isWhitespace() } + value.takeLastWhile { it.isWhitespace() }
        if (edges.any { it !in trimmedNameSpaces && it !in literalNameSpaces })
            reject(node, "Implicit Normal style-name whitespace is not qualified")
        return comparableName(value) == "Normal"
    }

    fun select(styles: Collection<XmlElement>, reject: (XmlElement, String) -> Nothing): XmlElement? {
        val paragraphs = styles.filter { it.attribute(word, "type") == "paragraph" }
        val declared = paragraphs.filter { it.attribute(word, "default") in enabled }
        if (declared.size > 1) reject(declared[1], "Multiple default paragraph styles are not qualified")
        declared.singleOrNull()?.let { return it }
        val normal = mutableListOf<XmlElement>()
        for (style in styles) {
            context.checkActive()
            val names = style.elements().filter { it.name.localPart == "name" }
            if (names.size > 1) reject(names[1], "Ambiguous implicit paragraph-style name")
            val name = names.singleOrNull()
            if (name != null && normalName(name, reject)) {
                if (name.name.namespaceURI != word || style.attribute(word, "type") != "paragraph")
                    reject(name, "Unqualified implicit Normal style type or namespace")
                normal += style
            }
            if (name == null && style.attribute(word, "styleId")?.equals("Normal", ignoreCase = true) == true)
                reject(style, "An implicit Normal style without a display name is not qualified")
            val aliases = style.elements().filter { it.name.localPart == "aliases" }
            if (aliases.any { it.attribute(word, "val")?.split(',')?.any { alias -> alias.trim().equals("Normal", true) } == true })
                reject(aliases.first(), "Implicit Normal aliases are not yet qualified")
        }
        if (normal.size > 1) reject(normal[1], "Ambiguous implicit Normal paragraph style")
        return normal.singleOrNull()
    }
}
