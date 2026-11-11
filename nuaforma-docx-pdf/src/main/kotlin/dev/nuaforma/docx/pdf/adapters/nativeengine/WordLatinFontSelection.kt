package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText

/** Bounded default-hint Latin font selection; never substitutes a font or widens script shaping. */
internal object WordLatinFontSelection {
    data class Segment(val text: String, val highAnsi: Boolean)
    private val scripts = setOf(Character.UnicodeScript.LATIN, Character.UnicodeScript.GREEK,
        Character.UnicodeScript.CYRILLIC, Character.UnicodeScript.COMMON)

    private val combining = setOf(Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt())

    private const val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val themes = setOf("majorEastAsia", "majorBidi", "majorAscii", "majorHAnsi", "minorEastAsia", "minorBidi", "minorAscii", "minorHAnsi")
    private val fontNames = setOf("ascii", "hAnsi", "cs", "eastAsia")
    private val themeNames = setOf("asciiTheme", "hAnsiTheme", "cstheme", "eastAsiaTheme")

    fun validateSource(root: XmlElement, reject: (XmlElement, String) -> Nothing) {
        for (properties in root.descendants().filter { it.name.namespaceURI == word && it.name.localPart == "rPr" }) {
            val fonts = properties.elements().filter { it.name.localPart == "rFonts" }
            if (fonts.size > 1) reject(fonts[1], "Duplicate original run-font property")
            fonts.forEach { validateFont(it, reject) }
        }
    }

    private fun validateFont(node: XmlElement, reject: (XmlElement, String) -> Nothing) {
        if (node.name.namespaceURI != word || node.elements().isNotEmpty() || node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() } ||
            node.attributes.keys.any { it.namespaceURI != word || it.localPart !in fontNames + themeNames + "hint" }) {
            reject(node, "Invalid original run-font property")
        }
        for (name in themeNames) node.attribute(word, name)?.let {
            if (it !in themes) reject(node, "Invalid original run-font theme role")
        }
        node.attribute(word, "hint")?.let {
            if (it !in setOf("default", "eastAsia", "cs")) reject(node, "Invalid original run-font hint")
        }
    }

    fun segments(text: String, context: GenerationContext, reject: (String) -> Nothing, budget: (Boolean) -> Unit): List<Segment> {
        if (text.isEmpty()) return emptyList()
        val result = mutableListOf<Segment>()
        var start = 0
        var offset = 0
        var selected = false

        fun emit(end: Int) {
            budget(result.size < 1_000)
            result += Segment(text.substring(start, end), selected)
            start = end
        }

        while (offset < text.length) {
            context.checkActive()
            val point = text.codePointAt(offset)
            val eastAsian = point in 0x2FF0..0x303F || point in 0xFE30..0xFE6F || point in 0xFF00..0xFFEF
            if (point > 0xFFFF || eastAsian || Character.UnicodeScript.of(point) !in scripts ||
                Character.getType(point) in combining) {
                reject("Mixed font selection for this character range is not qualified")
            }
            val highAnsi = point >= 128
            if (offset == 0) selected = highAnsi
            else if (selected != highAnsi) { emit(offset); selected = highAnsi }
            offset += Character.charCount(point)
        }
        emit(offset)
        return result
    }
}
