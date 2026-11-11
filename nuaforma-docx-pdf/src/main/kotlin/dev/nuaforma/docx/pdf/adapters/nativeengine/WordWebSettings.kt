package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText

/** Preserve only inert HTML round-trip metadata; the PDF reader still rejects effective divId bindings. */
internal class WordWebSettings(private val context: GenerationContext) {
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val children = mapOf(
        "webSettings" to setOf("divs", "optimizeForBrowser", "allowPNG", "relyOnVML", "targetScreenSz"),
        "divs" to setOf("div"), "divsChild" to setOf("div"),
        "div" to setOf("bodyDiv", "blockQuote", "marTop", "marBottom", "marLeft", "marRight", "divBdr", "divsChild"),
        "divBdr" to setOf("top", "bottom", "left", "right"),
    )
    private val borderAttributes = setOf("val", "sz", "space", "color", "themeColor", "themeTint", "themeShade", "shadow", "frame")

    fun normalize(root: XmlElement, failure: (XmlElement) -> NativeDocxReadException) {
        if (root.name.namespaceURI != word || root.name.localPart != "webSettings") throw failure(root)
        for (name in listOf("relyOnVML", "targetScreenSz")) {
            val entries = root.elements().filter { it.name.namespaceURI == word && it.name.localPart == name }
            if (entries.size > 1) throw failure(entries[1])
        }

        fun visit(node: XmlElement) {
            context.checkActive()
            val name = node.name.localPart
            val attributes = when (name) {
                "webSettings", "divs", "divsChild", "divBdr" -> emptySet()
                "div" -> setOf("id")
                "optimizeForBrowser" -> setOf("val", "target")
                "top", "bottom", "left", "right" -> borderAttributes
                else -> setOf("val")
            }
            if (node.name.namespaceURI != word || node.attributes.keys.any { it.namespaceURI != word || it.localPart !in attributes } ||
                node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() } ||
                node.elements().any { it.name.localPart !in children[name].orEmpty() }) throw failure(node)
            if (!scalar(node)) throw failure(node)
            for (child in node.elements()) visit(child)
        }
        visit(root)
        root.children.clear()
    }

    private fun scalar(node: XmlElement): Boolean = when (node.name.localPart) {
        "targetScreenSz" -> node.attribute(word, "val") in setOf("544x376", "640x480", "720x512", "800x600", "1024x768", "1152x882", "1152x900", "1280x1024", "1600x1200", "1800x1440", "1920x1200")
        "div" -> node.attribute(word, "id")?.toIntOrNull() != null
        "optimizeForBrowser", "allowPNG", "relyOnVML", "bodyDiv", "blockQuote" -> node.attribute(word, "val") in setOf(null, "true", "false", "1", "0", "on", "off")
        "marTop", "marBottom", "marLeft", "marRight" -> node.attribute(word, "val")?.toIntOrNull() != null
        else -> true
    }
}
