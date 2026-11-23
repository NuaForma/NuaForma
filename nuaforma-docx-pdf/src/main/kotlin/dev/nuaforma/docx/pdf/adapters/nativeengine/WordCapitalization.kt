package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText
import java.util.Locale
import javax.xml.namespace.QName

internal object WordCapitalization {
    private const val WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    fun normalizeSource(root: XmlElement, reject: (XmlElement, String) -> Nothing): Boolean {
        var changed = false
        for (properties in root.descendants().filter { it.name == QName(WORD, "rPr") }) {
            for (name in listOf("caps", "smallCaps")) {
                val nodes = properties.elements().filter { it.name.localPart == name }
                if (nodes.size > 1) reject(nodes[1], "Duplicate Word capitalization property")
                val node = nodes.singleOrNull() ?: continue
                val enabled = enabled(node, reject)
                val raw = node.attribute(WORD, "val") ?: continue
                if (raw != enabled.toString()) {
                    node.attributes[QName(WORD, "val")] = enabled.toString()
                    changed = true
                }
            }
        }
        return changed
    }

    fun enabled(node: XmlElement, reject: (XmlElement, String) -> Nothing): Boolean {
        if (node.name.namespaceURI != WORD || node.name.localPart !in setOf("caps", "smallCaps") ||
            node.attributes.keys.any { it != QName(WORD, "val") } ||
            node.elements().isNotEmpty() || node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() })
            reject(node, "Invalid Word capitalization property")
        return when (node.attribute(WORD, "val")) {
            null, "1", "true", "on" -> true
            "0", "false", "off" -> false
            else -> reject(node, "Invalid Word capitalization toggle")
        }
    }

    data class SmallCapSpan(val text: String, val reduced: Boolean)

    fun smallCaps(text: String, language: String?, limit: Int, checkActive: () -> Unit,
        reject: (String) -> Nothing): List<SmallCapSpan> {
        val spans = mutableListOf<SmallCapSpan>()
        val piece = StringBuilder()
        var reduced = false

        fun finish() {
            if (piece.isEmpty()) return
            if (spans.size >= limit) reject("Small caps exceed the paragraph run limit")
            val source = piece.toString()
            spans += SmallCapSpan(if (reduced) display(source, language, reject) else source, reduced)
            piece.setLength(0)
        }

        val points = text.codePoints().iterator()
        while (points.hasNext()) {
            checkActive()
            val point = points.nextInt()
            val lower = Character.isLowerCase(point)
            val category = Character.getType(point)
            if (category == Character.NON_SPACING_MARK.toInt() || category == Character.COMBINING_SPACING_MARK.toInt() ||
                category == Character.ENCLOSING_MARK.toInt() || (lower && Character.toUpperCase(point) == point) ||
                (Character.isLetter(point) && Character.UnicodeScript.of(point) != Character.UnicodeScript.LATIN))
                reject("Small caps outside the qualified single-glyph Latin case mapping")
            if (point != ' '.code && Character.isSpaceChar(point))
                reject("Small caps with nonstandard spaces are not qualified")
            val reducedGlyph = lower || point == ' '.code
            if (reduced != reducedGlyph) {
                finish()
                reduced = reducedGlyph
            }
            piece.appendCodePoint(point)
        }
        finish()
        return spans
    }

    fun display(text: String, language: String?, reject: (String) -> Nothing): String {
        val dottedI = language?.let(Locale::forLanguageTag)?.language == "tr"
        return buildString(text.length) {
            val points = text.codePoints().iterator()
            while (points.hasNext()) {
                val point = points.nextInt()
                val capital = if (dottedI && point == 'i'.code) 0x130 else Character.toUpperCase(point)
                if (point != capital && Character.UnicodeScript.of(point) != Character.UnicodeScript.LATIN)
                    reject("Capitalization outside the qualified Latin case mapping")
                appendCodePoint(capital)
            }
        }
    }

}
