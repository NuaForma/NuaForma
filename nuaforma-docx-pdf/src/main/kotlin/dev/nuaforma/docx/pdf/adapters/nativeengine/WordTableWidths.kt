package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText
import dev.nuaforma.pdf.PdfPreferredWidth

/** Validate original width preferences before a DOCX binding library can discard attributes. */
internal object WordTableWidths {
    private const val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    fun validateSource(root: XmlElement, reject: (XmlElement, String) -> Nothing) {
        for (parent in root.descendants()) if (parent.name.namespaceURI == word && parent.name.localPart in setOf("tblPr", "tcPr")) {
            for (name in listOf("tblW", "tcW", "tblLayout")) {
                val matches = parent.elements().filter { it.name.namespaceURI == word && it.name.localPart == name }
                if (matches.size > 1) reject(matches[1], "Duplicate table width property")
            }
        }
        for (node in root.descendants()) if (node.name.localPart in setOf("tblW", "tcW", "tblLayout")) {
            if (node.name.namespaceURI != word) reject(node, "Unsupported table width namespace")
            if (node.name.localPart == "tblLayout") {
                validate(node, setOf("type"), reject)
                if (node.attribute(word, "type") !in setOf("fixed", "autofit")) reject(node, "Unsupported table layout")
            } else preferred(node, reject)
        }
    }

    fun preferred(node: XmlElement, reject: (XmlElement, String) -> Nothing): PdfPreferredWidth? {
        validate(node, setOf("w", "type"), reject)
        val raw = node.attribute(word, "w") ?: reject(node, "Missing preferred width")
        if (!raw.matches(Regex("[0-9]{1,6}"))) reject(node, "Invalid preferred width measurement")
        val width = raw.toInt()
        return when (node.attribute(word, "type") ?: "dxa") {
            "auto", "nil" -> {
                if (width != 0) reject(node, "Automatic widths require zero measurement")
                null
            }
            "dxa" -> {
                if (width > 288_000) reject(node, "Invalid preferred physical width")
                PdfPreferredWidth.points(width / 20f)
            }
            "pct" -> {
                if (width > 5_000) reject(node, "Preferred percentage exceeds the containing width")
                PdfPreferredWidth.percent(width / 50f)
            }
            else -> reject(node, "Unsupported preferred width unit")
        }
    }

    private fun validate(node: XmlElement, attributes: Set<String>, reject: (XmlElement, String) -> Nothing) {
        if (node.attributes.keys.any { it.namespaceURI != word || it.localPart !in attributes } || node.elements().isNotEmpty() ||
            node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() }) reject(node, "Unsupported table width structure")
    }

}
