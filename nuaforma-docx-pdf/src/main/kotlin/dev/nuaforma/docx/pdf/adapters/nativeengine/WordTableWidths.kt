package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText
import dev.nuaforma.pdf.PdfPreferredWidth
import java.math.BigDecimal
import javax.xml.namespace.QName

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

    /** Canonicalize only qualified Word numeric spellings before integer-only DOCX binding. */
    fun normalizeSource(root: XmlElement, reject: (XmlElement, String) -> Nothing): Boolean {
        validateSource(root, reject)
        var changed = false
        for (node in root.descendants()) if (node.name.namespaceURI == word && node.name.localPart in setOf("tblW", "tcW")) {
            val raw = requireNotNull(node.attribute(word, "w"))
            val canonical = measurement(node, reject).toString()
            if (raw != canonical) {
                node.attributes[QName(word, "w")] = canonical
                changed = true
            }
        }
        return changed
    }

    fun preferred(node: XmlElement, reject: (XmlElement, String) -> Nothing): PdfPreferredWidth? {
        validate(node, setOf("w", "type"), reject)
        val width = measurement(node, reject)
        return when (node.attribute(word, "type") ?: "dxa") {
            "auto", "nil" -> null
            "dxa" -> PdfPreferredWidth.points(width / 20f)
            "pct" -> PdfPreferredWidth.percent(width / 50f)
            else -> reject(node, "Unsupported preferred width unit")
        }
    }

    private fun measurement(node: XmlElement, reject: (XmlElement, String) -> Nothing): Int {
        val raw = node.attribute(word, "w") ?: reject(node, "Missing preferred width")
        if (!raw.matches(Regex("[0-9]{1,6}(?:\\.[0-9]{1,6})?%?"))) reject(node, "Invalid preferred width measurement")
        val percent = raw.endsWith('%')
        val value = raw.removeSuffix("%").toBigDecimal()
        val unit = node.attribute(word, "type") ?: "dxa"
        if (percent && unit != "pct") reject(node, "Percentage spelling requires percentage width units")
        return when (unit) {
            "auto", "nil" -> {
                if (value.signum() != 0) reject(node, "Automatic widths require zero measurement")
                0
            }
            "dxa" -> {
                if (value > BigDecimal(288_000)) reject(node, "Invalid preferred physical width")
                // Word truncates positive fractional twips; do not round them upwards.
                value.toInt()
            }
            "pct" -> {
                val fiftieths = if (percent) value * BigDecimal(50) else value
                if (fiftieths > BigDecimal(5_000)) reject(node, "Preferred percentage exceeds the containing width")
                try { fiftieths.intValueExact() }
                catch (_: ArithmeticException) { reject(node, "Preferred percentage is not an exact fiftieth") }
            }
            else -> reject(node, "Unsupported preferred width unit")
        }
    }

    private fun validate(node: XmlElement, attributes: Set<String>, reject: (XmlElement, String) -> Nothing) {
        if (node.attributes.keys.any { it.namespaceURI != word || it.localPart !in attributes } || node.elements().isNotEmpty() ||
            node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() }) reject(node, "Unsupported table width structure")
    }

}
