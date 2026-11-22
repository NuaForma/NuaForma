package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
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

    /** Require all four explicit absent edges on each unmerged cell. */
    fun allCellsSuppressGrid(rows: List<XmlElement>, context: GenerationContext): Boolean {
        val sides = setOf("top", "right", "bottom", "left")
        val absent = setOf("nil", "none")
        val mergeProperties = setOf("gridSpan", "vMerge")
        return rows.all { row ->
            context.checkActive()
            val cells = row.elements().filter { it.name.namespaceURI == word && it.name.localPart == "tc" }
            cells.isNotEmpty() && cells.all { cell ->
                val props = cell.elements().singleOrNull { it.name.namespaceURI == word && it.name.localPart == "tcPr" }
                val borders = props?.elements()?.singleOrNull { it.name.namespaceURI == word && it.name.localPart == "tcBorders" }
                val edges = borders?.elements().orEmpty()
                props != null && props.elements().none { it.name.namespaceURI == word && it.name.localPart in mergeProperties } &&
                    edges.size == sides.size && edges.map { it.name.localPart }.toSet() == sides &&
                    edges.all { it.name.namespaceURI == word && it.attribute(word, "val") in absent }
            }
        }
    }

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

    fun removeInactiveDiagonals(root: XmlElement): Boolean {
        var changed = false
        for (properties in root.descendants().filter { it.name.namespaceURI == word && it.name.localPart == "tcPr" }) {
            val borders = properties.elements().singleOrNull {
                it.name.namespaceURI == word && it.name.localPart == "tcBorders"
            } ?: continue
            val inactive = borders.elements().filter { edge ->
                edge.name.namespaceURI == word && edge.name.localPart in setOf("tl2br", "tr2bl") &&
                    edge.attribute(word, "val") in setOf("none", "nil") &&
                    edge.attribute(word, "sz") in setOf(null, "0") && edge.attribute(word, "space") in setOf(null, "0") &&
                    edge.attribute(word, "color") in setOf(null, "auto") &&
                    edge.attributes.keys.all { it.namespaceURI == word && it.localPart in setOf("val", "sz", "space", "color") } &&
                    edge.elements().isEmpty() && edge.children.filterIsInstance<XmlText>().none { it.text.isNotBlank() }
            }
            if (inactive.isEmpty()) continue
            borders.children.removeAll(inactive.toSet())
            if (borders.elements().isEmpty()) properties.children.remove(borders)
            changed = true
        }
        return changed
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
        if (node.attribute(word, "val") == "single") node.attribute(word, "themeColor")?.let {
            if (!WordThemeColors.supportsBorderRole(it)) reject(node, "Unsupported original table border theme color")
        }
        for (name in listOf("themeTint", "themeShade")) node.attribute(word, name)?.let {
            if (!it.matches(Regex("[0-9A-Fa-f]{2}"))) reject(node, "Invalid table border theme transform")
        }
        for (name in listOf("shadow", "frame")) node.attribute(word, name)?.let {
            if (it !in setOf("0", "1", "true", "false", "on", "off")) reject(node, "Invalid table border toggle")
        }
    }

    fun read(node: XmlElement, theme: WordThemeColors, reject: (XmlElement, String) -> Nothing): PdfBorder? {
        if (node.name.namespaceURI != word || node.name.localPart !in edges || node.elements().isNotEmpty() ||
            node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() } ||
            node.attributes.keys.any { it.namespaceURI != word || it.localPart !in setOf("val", "sz", "color", "space", "themeColor", "themeTint", "themeShade") })
            reject(node, "Unsupported ordinary table border structure")
        if (node.attribute(word, "space") !in setOf(null, "0")) reject(node, "Only zero table border spacing is qualified")
        val kind = node.attribute(word, "val")
        if (kind !in setOf("single", "none", "nil")) reject(node, "Only ordinary solid or absent table borders are qualified")
        val raw = node.attribute(word, "sz")
        val size = raw?.toIntOrNull()
        if (raw != null && (size == null || size !in 0..48 || !raw.matches(Regex("[0-9]{1,10}")))) reject(node, "Invalid table border width")
        val color = node.attribute(word, "color")
        if (color != null && color != "auto" && !color.matches(Regex("[0-9A-Fa-f]{6}"))) reject(node, "Invalid table border color")
        val role = node.attribute(word, "themeColor")
        val tint = node.attribute(word, "themeTint")
        val shade = node.attribute(word, "themeShade")
        if (tint != null || shade != null) {
            if (kind != "single" || role == null || role == "none" || tint != null && shade != null ||
                (tint ?: shade)?.matches(Regex("[0-9A-Fa-f]{2}")) != true)
                reject(node, "A table border tint or shade requires one qualified theme transform")
        }
        if (kind != "single") {
            if (role != null) reject(node, "A themed border requires a solid line")
            return null
        }
        if (size == null || size !in 1..48) reject(node, "Invalid solid table border width")
        val themed = role?.let { theme.borderColor(node, it) }
        if (themed != null) return PdfBorder(size / 8f, themed)
        if (color == null) reject(node, "Missing solid table border color")
        val rgb = if (color == "auto") 0 else color.toInt(16)
        return PdfBorder(size / 8f, PdfColor(rgb shr 16, rgb shr 8 and 255, rgb and 255))
    }

}
