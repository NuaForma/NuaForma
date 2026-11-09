package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText
import dev.nuaforma.pdf.PdfBorder
import dev.nuaforma.pdf.PdfColor

/** Bounded ordinary table borders, with original-XML validation before DOCX binding. */
internal object WordTableBorders {
    private const val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val edges = setOf("top", "left", "bottom", "right", "insideH", "insideV")
    private val originalEdges = edges + setOf("start", "end", "tl2br", "tr2bl")
    private val originalAttributes = setOf("val", "sz", "color", "space", "themeColor", "themeTint", "themeShade", "shadow", "frame")

    fun validateSource(root: XmlElement, reject: (XmlElement, String) -> Nothing) {
        for (parent in root.descendants()) if (parent.name.namespaceURI == word && parent.name.localPart in setOf("tblBorders", "tcBorders")) {
            if (parent.attributes.isNotEmpty() || parent.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() })
                reject(parent, "Unsupported table border container")
            val seen = mutableSetOf<String>()
            for (node in parent.elements()) {
                if (!seen.add(node.name.localPart)) reject(node, "Duplicate table border edge")
                validateOriginal(node, reject)
            }
        }
    }

    private fun validateOriginal(node: XmlElement, reject: (XmlElement, String) -> Nothing) {
        if (node.name.namespaceURI != word || node.name.localPart !in originalEdges || node.elements().isNotEmpty() ||
            node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() } ||
            node.attributes.keys.any { it.namespaceURI != word || it.localPart !in originalAttributes })
            reject(node, "Unsupported original table border structure")
        for (name in listOf("sz", "space")) node.attribute(word, name)?.let { raw ->
            if (!raw.matches(Regex("[0-9]{1,10}")) || raw.toLongOrNull()?.let { it <= Int.MAX_VALUE } != true)
                reject(node, "Invalid original table border measurement")
        }
        node.attribute(word, "color")?.let { if (it != "auto" && !it.matches(Regex("[0-9A-Fa-f]{6}"))) reject(node, "Invalid table border color") }
        for (name in listOf("themeTint", "themeShade")) node.attribute(word, name)?.let {
            if (!it.matches(Regex("[0-9A-Fa-f]{2}"))) reject(node, "Invalid table border theme transform")
        }
        for (name in listOf("shadow", "frame")) node.attribute(word, name)?.let {
            if (it !in setOf("0", "1", "true", "false", "on", "off")) reject(node, "Invalid table border toggle")
        }
    }

    fun read(node: XmlElement, reject: (XmlElement, String) -> Nothing): PdfBorder? {
        if (node.name.namespaceURI != word || node.name.localPart !in edges || node.elements().isNotEmpty() ||
            node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() } ||
            node.attributes.keys.any { it.namespaceURI != word || it.localPart !in setOf("val", "sz", "color", "space") })
            reject(node, "Unsupported ordinary table border structure")
        if (node.attribute(word, "space") !in setOf(null, "0")) reject(node, "Only zero table border spacing is qualified")
        val kind = node.attribute(word, "val")
        if (kind !in setOf("single", "none", "nil")) reject(node, "Only ordinary solid or absent table borders are qualified")
        val raw = node.attribute(word, "sz")
        val size = raw?.toIntOrNull()
        if (raw != null && (size == null || size !in 0..32 || !raw.matches(Regex("[0-9]{1,10}")))) reject(node, "Invalid table border width")
        val color = node.attribute(word, "color")
        if (color != null && color != "auto" && !color.matches(Regex("[0-9A-Fa-f]{6}"))) reject(node, "Invalid table border color")
        if (kind != "single") return null
        if (size == null || size !in 1..32) reject(node, "Invalid solid table border width")
        if (color == null) reject(node, "Missing solid table border color")
        val rgb = if (color == "auto") 0 else color.toInt(16)
        return PdfBorder(size / 8f, PdfColor(rgb shr 16, rgb shr 8 and 255, rgb and 255))
    }

}
