package dev.nuaforma.docx.adapters.docx4j

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.internal.BoundedStreams
import dev.nuaforma.docx.internal.DocxFailure
import org.docx4j.openpackaging.packages.WordprocessingMLPackage
import org.docx4j.openpackaging.parts.JaxbXmlPart
import dev.nuaforma.docx.adapters.docx4j.FontXmlElement as Element
import java.io.ByteArrayOutputStream

/** Effective fonts for the bounded Latin WordprocessingML profile, after template expansion. */
internal class UsedWordFonts(private val document: WordprocessingMLPackage, private val context: GenerationContext) {
    data class Face(val family: String, val face: String)
    private data class Family(val name: String, val theme: Boolean)
    private data class Formatting(val fonts: MutableMap<String, Family> = mutableMapOf(), var bold: Boolean = false, var italic: Boolean = false) {
        fun copyState() = Formatting(fonts.toMutableMap(), bold, italic)
    }
    private val parts = linkedMapOf<String, Element>()
    private val required = linkedSetOf<Face>()
    private val styles: Map<String, Element>
    private val defaults: Element?
    private val defaultParagraph: String?
    private val defaultCharacter: String?

    init {
        var total = 0L
        val xml = FontXml(context)
        for (part in document.parts.parts.values.filterIsInstance<JaxbXmlPart<*>>()) {
            context.checkActive()
            val name = part.getPartName().name
            if (!name.startsWith("/word/") || !name.endsWith(".xml")) continue
            DocxFailure.inPart(name.removePrefix("/")) {
                val bytes = ByteArrayOutputStream()
                part.marshal(BoundedStreams.Output(bytes, context.limits.maxExpandedBytes - total, context))
                total += bytes.size()
                parts[name] = xml.read(bytes.toByteArray())
            }
        }
        val root = parts["/word/styles.xml"]
        val definitions = root?.children("style") ?: emptyList()
        if (definitions.map { it.attr("styleId") }.distinct().size != definitions.size) inheritance()
        styles = definitions.associateBy { it.attr("styleId") }
        defaults = root?.child("docDefaults")?.child("rPrDefault")?.child("rPr")
        fun defaultStyle(type: String): String? {
            val matches = definitions.filter { it.attr("type") == type && it.attr("default") in setOf("1", "true", "on") }
            if (matches.size > 1) inheritance()
            return matches.singleOrNull()?.attr("styleId")
        }
        defaultParagraph = defaultStyle("paragraph")
        defaultCharacter = defaultStyle("character")
    }

    fun collect(): Set<Face> {
        val stories = storyParts()
        for ((name, part) in parts) {
            DocxFailure.inPart(name.removePrefix("/")) {
                validateAuxiliary(name, part)
                if (name !in stories) return@inPart
                for (element in part.descendants()) {
                    context.checkActive()
                    inspect(element)
                }
            }
        }
        return required
    }

    private fun validateAuxiliary(name: String, root: Element) {
        if (root.localName in setOf("footnotes", "endnotes", "comments") && root.elements(WORD, "t").isNotEmpty()) unsupported(RejectionReason.AUTOMATIC_FONT_UNSUPPORTED_PART)
        if (name.contains("/charts/") || name.contains("/diagrams/")) unsupported(RejectionReason.AUTOMATIC_FONT_UNSUPPORTED_PART)
    }

    private fun storyParts(): Set<String> {
        val main = parts["/word/document.xml"] ?: inheritance()
        val stories = mutableSetOf("/word/document.xml")
        for (element in main.descendants().filter { it.namespaceURI == WORD && it.localName in setOf("headerReference", "footerReference") }) {
            val reference = element.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
            val part = document.mainDocumentPart.relationshipsPart.getPart(reference) ?: inheritance()
            stories.add(part.getPartName().name)
        }
        return stories
    }

    private fun inspect(element: Element) {
        when (element.namespaceURI) {
            VML -> if (element.localName == "textpath" && element.getAttribute("string").isNotEmpty()) unsupported(RejectionReason.STRICT_VML_TEXT_PATH_FONT_RESOLUTION_IS_NOT_SUPPORTED)
            DRAWING -> if (element.localName == "t" && element.textContent.isNotEmpty()) unsupported(RejectionReason.AUTOMATIC_FONT_UNSUPPORTED_DRAWING_TEXT)
            WORD -> when (element.localName) {
                "tblStyle" -> unsupported(RejectionReason.AUTOMATIC_FONT_UNSUPPORTED_TABLE_STYLE)
                "del", "ins", "moveFrom", "moveTo" -> unsupported(RejectionReason.AUTOMATIC_FONT_UNSUPPORTED_REVISIONS)
                "tbl" -> if (styles.values.any(::hasDefaultTableFonts)) unsupported(RejectionReason.AUTOMATIC_FONT_UNSUPPORTED_TABLE_STYLE)
                "p" -> paragraph(element)
            }
        }
    }

    private fun hasDefaultTableFonts(style: Element): Boolean = style.attr("type") == "table" &&
        style.attr("default") in setOf("1", "true", "on") && (style.child("rPr") != null || style.children("tblStylePr").isNotEmpty())

    private fun paragraph(paragraph: Element) {
        val base = Formatting()
        apply(base, defaults, false)
        val ppr = paragraph.child("pPr")
        val chain = chain(ppr?.child("pStyle")?.attr("val") ?: defaultParagraph, "paragraph")
        chain.forEach { apply(base, it.child("rPr"), true) }
        val num = chain.mapNotNull { it.child("pPr")?.child("numPr") } + listOfNotNull(ppr?.child("numPr"))
        val marker = base.copyState()
        chain.forEach { apply(marker, it.child("pPr")?.child("rPr"), true) }
        apply(marker, ppr?.child("rPr"), false)
        numbering(num, marker)
        for (run in paragraph.descendants().filter { it.namespaceURI == WORD && it.localName == "r" && it.nearest("p") === paragraph }) {
            val state = base.copyState()
            val rpr = run.child("rPr")
            chain(rpr?.child("rStyle")?.attr("val") ?: defaultCharacter, "character").forEach { apply(state, it.child("rPr"), true) }
            apply(state, rpr, false)
            for (text in run.children("t")) add(state, text.textContent)
            for (symbol in run.children("sym")) {
                val family = symbol.attr("font")
                if (family.isBlank()) unsupported()
                record(family, state)
            }
        }
    }

    private fun numbering(properties: List<Element>, state: Formatting) {
        val id = properties.mapNotNull { it.child("numId")?.attr("val") }.lastOrNull() ?: return
        if (id == "0") return
        val level = properties.mapNotNull { it.child("ilvl")?.attr("val") }.lastOrNull() ?: "0"
        val root = parts["/word/numbering.xml"] ?: inheritance()
        val num = root.children("num").singleOrNull { it.attr("numId") == id } ?: inheritance()
        val abstractId = num.child("abstractNumId")?.attr("val") ?: inheritance()
        val abstract = root.children("abstractNum").singleOrNull { it.attr("abstractNumId") == abstractId } ?: inheritance()
        if (abstract.child("numStyleLink") != null) unsupported()
        val override = num.children("lvlOverride").singleOrNull { it.attr("ilvl") == level }?.child("lvl")
        val definition = override ?: abstract.children("lvl").singleOrNull { it.attr("ilvl") == level } ?: inheritance()
        if (definition.child("lvlPicBulletId") != null || definition.child("numFmt")?.attr("val") !in setOf("decimal", "decimalZero", "upperRoman", "lowerRoman", "upperLetter", "lowerLetter", "bullet", "none")) unsupported()
        if (definition.child("numFmt")?.attr("val") == "none") return
        val number = state.copyState()
        apply(number, definition.child("rPr"), false)
        add(number, definition.child("lvlText")?.attr("val") ?: inheritance())
    }

    private fun chain(id: String?, type: String): List<Element> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<Element>()
        var current = id
        while (current != null) {
            context.checkActive()
            if (!seen.add(current) || seen.size > 64) inheritance()
            val style = styles[current] ?: inheritance()
            if (style.attr("type") != type) inheritance()
            result.add(style)
            current = style.child("basedOn")?.attr("val")
        }
        return result.reversed()
    }

    private fun apply(state: Formatting, properties: Element?, toggle: Boolean) {
        if (properties == null) return
        if (properties.children("rtl").any(::enabled) || properties.children("cs").any(::enabled)) unsupported(RejectionReason.AUTOMATIC_FONT_UNSUPPORTED_SCRIPT)
        properties.child("rFonts")?.let { fonts ->
            for (slot in listOf("ascii", "hAnsi")) {
                val theme = fonts.attr(slot + "Theme")
                val explicit = fonts.attr(slot)
                if (theme.isNotEmpty()) state.fonts[slot] = Family(theme, true)
                else if (explicit.isNotEmpty()) state.fonts[slot] = Family(explicit, false)
            }
            if (fonts.attr("hint") in setOf("eastAsia", "cs")) unsupported(RejectionReason.AUTOMATIC_FONT_UNSUPPORTED_SCRIPT)
        }
        properties.child("b")?.let { state.bold = if (toggle) state.bold xor enabled(it) else enabled(it) }
        properties.child("i")?.let { state.italic = if (toggle) state.italic xor enabled(it) else enabled(it) }
    }

    private fun add(state: Formatting, text: String) {
        if (text.isEmpty()) return
        val slots = mutableSetOf<String>()
        var offset = 0
        while (offset < text.length) {
            context.checkActive()
            val point = text.codePointAt(offset)
            if (Character.UnicodeScript.of(point) !in setOf(Character.UnicodeScript.LATIN, Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED)) unsupported(RejectionReason.AUTOMATIC_FONT_UNSUPPORTED_SCRIPT)
            slots.add(if (point < 128) "ascii" else "hAnsi")
            offset += Character.charCount(point)
        }
        for (slot in slots) {
            val family = state.fonts[slot] ?: unsupported()
            record(if (family.theme) theme(family.name) else family.name, state)
        }
    }

    private fun theme(value: String): String {
        val group = when (value) { "majorAscii", "majorHAnsi" -> "majorFont"; "minorAscii", "minorHAnsi" -> "minorFont"; else -> unsupported(RejectionReason.AUTOMATIC_FONT_UNRESOLVED_THEME) }
        val themes = parts.values.filter { it.namespaceURI == DRAWING && it.localName == "theme" }
        val root = themes.singleOrNull() ?: unsupported(RejectionReason.AUTOMATIC_FONT_UNRESOLVED_THEME)
        return root.elements(DRAWING, group).let { nodes ->
            if (nodes.size != 1) unsupported(RejectionReason.AUTOMATIC_FONT_UNRESOLVED_THEME)
            val latin = nodes.single().elements(DRAWING, "latin")
            if (latin.size != 1) unsupported(RejectionReason.AUTOMATIC_FONT_UNRESOLVED_THEME)
            latin.single().getAttribute("typeface").takeIf { it.isNotBlank() && !it.startsWith('+') } ?: unsupported(RejectionReason.AUTOMATIC_FONT_UNRESOLVED_THEME)
        }
    }

    private fun record(family: String, state: Formatting) {
        if (family.isBlank() || family.length > 128 || family.any(Char::isISOControl)) unsupported()
        val face = if (state.bold && state.italic) "boldItalic" else if (state.bold) "bold" else if (state.italic) "italic" else "regular"
        required.add(Face(family, face))
        if (required.size > 64) DocxFailure.fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, RejectionReason.FONTS_MUST_BE_AN_ARRAY_WITH_AT_MOST_64)
    }

    private fun enabled(element: Element): Boolean = when (element.attr("val")) {
        "", "1", "true", "on" -> true
        "0", "false", "off" -> false
        else -> unsupported()
    }
    private fun Element.attr(name: String) = getAttributeNS(WORD, name)
    private fun Element.children(name: String): List<Element> = children.filter { it.namespaceURI == WORD && it.localName == name }
    private fun Element.child(name: String): Element? = children(name).also { if (it.size > 1) inheritance() }.singleOrNull()
    private fun Element.nearest(name: String): Element? {
        var node = parentNode
        while (node != null) { if (node.namespaceURI == WORD && node.localName == name) return node; node = node.parentNode }
        return null
    }
    private fun unsupported(reason: RejectionReason = RejectionReason.UNSUPPORTED_EFFECTIVE_FONT_CONSTRUCT): Nothing = DocxFailure.fail(DiagnosticCodes.UNSUPPORTED_INPUT, reason)
    private fun inheritance(): Nothing = DocxFailure.fail(DiagnosticCodes.INVALID_REQUEST, RejectionReason.INVALID_FONT_STYLE_INHERITANCE)

    companion object {
        private const val WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        private const val DRAWING = "http://schemas.openxmlformats.org/drawingml/2006/main"
        private const val VML = "urn:schemas-microsoft-com:vml"
    }
}
