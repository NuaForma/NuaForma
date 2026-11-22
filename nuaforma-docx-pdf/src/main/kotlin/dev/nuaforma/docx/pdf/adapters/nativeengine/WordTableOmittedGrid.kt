package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText
import kotlin.math.abs

/** Qualifies omitted edge slots whose specified widths agree with the original physical grid. */
internal object WordTableOmittedGrid {
    private const val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    fun validateSource(root: XmlElement, reject: (XmlElement, String) -> Nothing) {
        for (node in root.descendants().filter { it.name.namespaceURI == word && it.name.localPart == "trPr" }) {
            side(node, "Before", reject)
            side(node, "After", reject)
        }
    }

    fun read(properties: XmlElement?, widths: List<Float>, reject: (XmlElement, String) -> Nothing): Pair<Int, Int> {
        if (properties == null) return 0 to 0
        val (leading, before) = side(properties, "Before", reject)
        val (trailing, after) = side(properties, "After", reject)
        if (leading + trailing >= widths.size) reject(properties, "Omitted columns leave no table cells")
        before?.let { if (abs(it.first / 20f - widths.take(leading).sum()) > .001f)
            reject(it.second, "Leading omission width differs from the declared table grid") }
        after?.let { if (abs(it.first / 20f - widths.takeLast(trailing).sum()) > .001f)
            reject(it.second, "Trailing omission width differs from the declared table grid") }
        return leading to trailing
    }

    private fun side(properties: XmlElement, edge: String, reject: (XmlElement, String) -> Nothing): Pair<Int, Pair<Int, XmlElement>?> {
        fun node(name: String, allowed: Set<String>): XmlElement? {
            val entries = properties.elements().filter { it.name.localPart == name }
            if (entries.size > 1) reject(entries[1], "Duplicate table grid omission")
            val selected = entries.singleOrNull() ?: return null
            if (selected.name.namespaceURI != word || selected.elements().isNotEmpty() ||
                selected.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() } ||
                selected.attributes.keys.any { it.namespaceURI != word || it.localPart !in allowed })
                reject(selected, "Invalid table grid omission")
            return selected
        }

        val count = node("grid$edge", setOf("val"))?.let { value ->
            value.attribute(word, "val")?.toIntOrNull()?.takeIf { it in 0..15 }
                ?: reject(value, "Invalid omitted table-column count")
        } ?: 0
        val width = node("w$edge", setOf("type", "w"))?.let { value ->
            val twips = value.attribute(word, "w")?.toIntOrNull()
            if (value.attribute(word, "type") != "dxa" || twips == null || twips !in 0..288_000 ||
                (count == 0) != (twips == 0)) reject(value, "Omitted column width requires a matching physical grid edge")
            twips to value
        }
        return count to width
    }
}
