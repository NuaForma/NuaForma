package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText

/** Retain LTR page-band geometry when Word stores complex-script hints only on nonprinting controls. */
internal object WordHeaderControlDirection {
    private const val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val active = setOf(null, "1", "true", "on")

    fun normalize(parts: Map<String, XmlElement>, context: GenerationContext) {
        val settings = parts["word/settings.xml"] ?: return
        val compatibility = settings.elements().singleOrNull { it.word("compat") } ?: return
        val flag = compatibility.elements().singleOrNull { it.word("useFELayout") } ?: return
        if (!leaf(flag, setOf("val")) || flag.attribute(word, "val") !in active) return
        val styleRoots = listOfNotNull(parts["word/styles.xml"], parts["word/stylesWithEffects.xml"])
        if (styleRoots.any { style -> style.descendants().any { it.word("tab") && it.attribute(word, "leader") !in setOf(null, "none") } }) return
        val markings = mutableMapOf<XmlElement, XmlElement>()
        for ((path, root) in parts) {
            if (!path.matches(Regex("word/header[1-9][0-9]?\\.xml")) || !root.word("hdr")) continue
            for (paragraph in root.elements().filter { it.word("p") }) {
                context.checkActive()
                collect(paragraph, markings)
            }
        }
        if (markings.isEmpty() || WordAuthoringMetadata.containsComplexText(parts.values, markings.keys)) return
        for ((direction, style) in markings) {
            style.children.remove(direction)
            val fonts = style.elements().single { it.word("rFonts") }
            fonts.attributes.keys.removeIf { it.namespaceURI == word && it.localPart == "hint" }
        }
    }

    private fun collect(paragraph: XmlElement, markings: MutableMap<XmlElement, XmlElement>) {
        val nodes = paragraph.elements()
        if (nodes.any { !it.word("pPr") && !it.word("r") } ||
            paragraph.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() }) return
        val properties = nodes.singleOrNull { it.word("pPr") } ?: return
        val tabs = properties.elements().singleOrNull { it.word("tabs") } ?: return
        if (properties.elements().any { it.word("bidi") || it.word("numPr") } || tabs.elements().isEmpty() ||
            tabs.elements().any { !it.word("tab") || it.attribute(word, "leader") !in setOf(null, "none") }) return

        val runs = nodes.filter { it.word("r") }
        val content = runs.map { run -> run.elements().filterNot { it.word("rPr") } }
        if (content.any { it.size != 1 || (!it[0].word("tab") && !it[0].word("t")) }) return
        if (content.none { it[0].word("tab") } ||
            content.any { it[0].word("t") && !latinText(it[0]) }) return
        val candidates = runs.zip(content).mapNotNull { (run, payload) ->
            run.elements().singleOrNull {
                payload.single().word("tab") && leaf(payload.single(), emptySet()) && it.word("rPr")
            }
        } + listOfNotNull(properties.elements().singleOrNull { it.word("rPr") })
        for (style in candidates) {
            val direction = qualifiedDirection(style) ?: continue
            markings[direction] = style
        }
    }

    private fun qualifiedDirection(properties: XmlElement): XmlElement? {
        if (properties.attributes.isNotEmpty() || properties.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() }) return null
        val fields = properties.elements()
        if (fields.size != 5 || fields.any { it.name.namespaceURI != word } || fields.map { it.name }.distinct().size != 5) return null
        val normal = fields.singleOrNull { it.word("sz") } ?: return null
        val complex = fields.singleOrNull { it.word("szCs") } ?: return null
        val size = normal.attribute(word, "val")?.toIntOrNull()
        if (size == null || size !in 1..1_638 || !leaf(normal, setOf("val")) || !leaf(complex, setOf("val")) ||
            complex.attribute(word, "val")?.toIntOrNull() != size) return null
        val fonts = fields.singleOrNull { it.word("rFonts") } ?: return null
        val family = fonts.attribute(word, "ascii")
        if (!leaf(fonts, setOf("ascii", "hAnsi", "hint")) || family.isNullOrBlank() || fonts.attribute(word, "hAnsi") != family ||
            fonts.attribute(word, "hint") !in setOf(null, "cs")) return null
        val language = fields.singleOrNull { it.word("lang") } ?: return null
        val tag = language.attribute(word, "bidi")
        if (!leaf(language, setOf("bidi")) || tag?.matches(Regex("[A-Za-z]{2,3}-[A-Za-z]{2}")) != true) return null
        return fields.singleOrNull { it.word("rtl") && leaf(it, setOf("val")) && it.attribute(word, "val") in active }
    }

    private fun latinText(node: XmlElement) = node.children.filterIsInstance<XmlText>().all { text ->
        text.text.codePoints().allMatch { point ->
            Character.UnicodeScript.of(point) in setOf(Character.UnicodeScript.LATIN, Character.UnicodeScript.COMMON) &&
                Character.getDirectionality(point) !in setOf(Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) &&
                Character.getType(point) != Character.FORMAT.toInt()
        }
    }

    private fun leaf(node: XmlElement, attributes: Set<String>) =
        node.attributes.keys.all { it.namespaceURI == word && it.localPart in attributes } &&
            node.elements().isEmpty() && node.children.filterIsInstance<XmlText>().none { it.text.isNotBlank() }

    private fun XmlElement.word(name: String): Boolean = this.name.namespaceURI == word && this.name.localPart == name
}
