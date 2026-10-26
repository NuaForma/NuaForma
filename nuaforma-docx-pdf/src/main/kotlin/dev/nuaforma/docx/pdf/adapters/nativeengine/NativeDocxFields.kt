package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText
import java.util.Locale
import javax.xml.namespace.QName

/** Parse a closed, inert field grammar. Cached results are formatting samples, never field values. */
internal object NativeDocxFields {
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    data class Instruction(val kind: String, val argument: String? = null, val linked: Boolean = false,
        val restart: Int? = null, val repeat: Boolean = false, val headingReset: Int? = null,
        val firstLevel: Int = 0, val lastLevel: Int = 2, val directOutlineLevels: Boolean = false)

    fun instruction(text: String, fail: (String) -> Nothing): Instruction {
        if (text.length !in 1..2_048 || text.any { it.isISOControl() && !it.isWhitespace() }) fail("Invalid field instruction")
        val tokens = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            if (text[index].isWhitespace()) { index++; continue }
            val quoted = text[index] == '"'
            if (quoted) index++
            val start = index
            while (index < text.length && if (quoted) text[index] != '"' else !text[index].isWhitespace()) index++
            if (quoted && index == text.length) fail("Unclosed field argument")
            tokens += text.substring(start, index)
            if (quoted) { index++; if (index < text.length && !text[index].isWhitespace()) fail("Invalid field argument separator") }
            if (tokens.size > 32) fail("Excessive field arguments")
        }
        if (tokens.isEmpty()) fail("Empty field instruction")
        val kind = tokens.first().uppercase(Locale.ROOT)
        if (kind !in setOf("SEQ", "REF", "PAGEREF", "PAGE", "NUMPAGES", "SECTIONPAGES", "TOC")) fail("Unsupported field instruction")
        var cursor = 1
        fun next() = tokens.getOrNull(cursor++) ?: fail("Missing field argument")
        val argument = if (kind in setOf("SEQ", "REF", "PAGEREF")) next().also {
            if (!it.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,127}"))) fail("Unsupported field identifier")
            if (kind == "SEQ" && !it.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,39}"))) fail("Unsupported sequence identifier")
        } else null
        var linked = false; var restart: Int? = null; var repeat = false; var reset: Int? = null
        var first = 0; var last = 2
        val seen = mutableSetOf<String>()
        while (cursor < tokens.size) {
            val switch = next().lowercase(Locale.ROOT)
            if (switch == "\\*") {
                val format = next().uppercase(Locale.ROOT)
                if (format !in setOf("ARABIC", "MERGEFORMAT") || !seen.add("$switch:$format")) fail("Unsupported or duplicate field formatting")
                continue
            }
            if (!seen.add(switch)) fail("Duplicate field switch")
            when {
                kind == "SEQ" && switch == "\\r" -> restart = next().toIntOrNull()?.takeIf { it in 0..9_999 } ?: fail("Invalid sequence restart")
                kind == "SEQ" && switch == "\\s" -> reset = next().toIntOrNull()?.takeIf { it in 1..9 }?.minus(1) ?: fail("Invalid heading reset")
                kind == "SEQ" && switch == "\\c" -> repeat = true
                kind == "SEQ" && switch == "\\n" -> Unit
                kind in setOf("REF", "PAGEREF", "TOC") && switch == "\\h" -> linked = true
                kind == "TOC" && switch == "\\o" -> {
                    val range = next().split('-').map { it.toIntOrNull() ?: fail("Invalid contents levels") }
                    if (range.size != 2 || range[0] !in 1..9 || range[1] !in range[0]..9) fail("Invalid contents levels")
                    first = range[0] - 1; last = range[1] - 1
                }
                kind == "TOC" && switch in setOf("\\u", "\\z") -> Unit
                else -> fail("Unsupported field switch")
            }
        }
        if (kind == "TOC" && "\\o" !in seen) fail("Contents require explicit outline levels")
        if (kind == "SEQ" && (repeat && (restart != null || reset != null || "\\n" in seen) || restart != null && reset != null)) fail("Conflicting sequence switches")
        return Instruction(kind, argument, linked, restart, repeat, reset, first, last, "\\u" in seen)
    }

    /** Collapse only complete complex fields; a multi-paragraph field must be a standalone TOC. */
    fun normalize(root: XmlElement, fail: (String) -> Nothing) {
        fun value(node: XmlElement, name: String) = node.attribute(W, name)
        fun local(node: XmlElement, name: String) = node.name.namespaceURI == W && node.name.localPart == name
        fun control(node: XmlElement): XmlElement? = if (local(node, "r")) node.elements().singleOrNull { local(it, "fldChar") } else null
        fun validateControl(run: XmlElement, marker: XmlElement): String {
            if (run.elements().any { it !== marker && !local(it, "rPr") } || marker.elements().isNotEmpty() ||
                marker.attributes.keys.any { it.namespaceURI != W || it.localPart !in setOf("fldCharType", "dirty", "fldLock") } ||
                value(marker, "fldLock") !in setOf(null, "0", "false", "off")) fail("Unsupported field control")
            value(marker, "dirty")?.let { if (it !in setOf("0", "1", "false", "true", "on", "off")) fail("Invalid field state") }
            return value(marker, "fldCharType") ?: fail("Missing field control type")
        }
        // A Word-generated TOC can be wrapped in its identified built-in content control.
        for (parent in root.descendants().toList()) for (node in parent.elements().toList()) if (local(node, "sdt")) {
            val properties = node.elements().singleOrNull { local(it, "sdtPr") }
            val gallery = properties?.descendants()?.singleOrNull { local(it, "docPartGallery") }?.let { value(it, "val") }
            if (gallery != "Table of Contents") continue // Other controls remain visible to the rejecting reader.
            if (properties.elements().any { !local(it, "id") && !local(it, "docPartObj") } ||
                node.elements().any { !local(it, "sdtPr") && !local(it, "sdtContent") }) fail("Unsupported contents control")
            if (properties.descendants().any { it.name.namespaceURI != W || it.name.localPart !in setOf("sdtPr", "id", "docPartObj", "docPartGallery", "docPartUnique") ||
                it.attributes.keys.any { key -> key.namespaceURI != W || key.localPart != "val" } }) fail("Unsupported contents control property")
            val content = node.elements().singleOrNull { local(it, "sdtContent") } ?: fail("Missing contents control body")
            if (content.elements().none { p -> p.descendants().any { local(it, "instrText") && it.children.filterIsInstance<XmlText>().joinToString("") { t -> t.text }.trimStart().startsWith("TOC", true) } }) fail("Contents control has no contents field")
            val at = parent.children.indexOf(node)
            parent.children.removeAt(at); parent.children.addAll(at, content.children)
        }
        fun simple(code: String, cached: List<XmlElement>): List<XmlElement> {
            val contents = instruction(code, fail).kind == "TOC"
            val runs = cached.filter { local(it, "r") }
            val bookmarks = cached.filter { !local(it, "r") }
            if (!contents) for (marker in bookmarks) {
                val at = cached.indexOf(marker)
                if (local(marker, "bookmarkStart") && cached.take(at).any { local(it, "r") } ||
                    local(marker, "bookmarkEnd") && cached.drop(at + 1).any { local(it, "r") }) fail("Partial field-result bookmarks are not qualified")
            }
            val field = XmlElement(QName(W, "fldSimple", "w"), linkedMapOf(QName(W, "instr", "w") to code)).also { it.children.addAll(runs) }
            return if (contents) listOf(field) else bookmarks.filter { local(it, "bookmarkStart") } + field + bookmarks.filter { local(it, "bookmarkEnd") }
        }
        for (container in root.descendants().filter { it.name.namespaceURI == W && it.name.localPart in setOf("body", "tc", "hdr", "ftr", "txbxContent") }.toList()) {
            var beginning: XmlElement? = null
            var depth = 0
            var separated = false
            val code = StringBuilder()
            val cached = mutableListOf<XmlElement>()
            var multi = false
            for (paragraph in container.elements().toList()) {
                if (!local(paragraph, "p")) { if (depth != 0) fail("Field crosses a structural boundary"); continue }
                val output = mutableListOf<dev.nuaforma.docx.pdf.adapters.ooxml.XmlNode>()
                if (depth != 0) multi = true
                for (node in paragraph.children) {
                    if (node !is XmlElement) { if (depth == 0) output += node; continue }
                    if (local(node, "pPr")) { output += node; continue }
                    val marker = control(node)
                    if (marker != null) {
                        when (validateControl(node, marker)) {
                            "begin" -> {
                                if (depth == 0) { beginning = paragraph; code.setLength(0); cached.clear(); separated = false; multi = false }
                                else if (!separated || instruction(code.toString(), fail).kind != "TOC") fail("Nested fields are outside this profile")
                                if (++depth > 16) fail("Excessively nested field cache")
                            }
                            "separate" -> { if (depth == 0 || depth == 1 && separated) fail("Unmatched field separator"); if (depth == 1) separated = true }
                            "end" -> {
                                if (depth == 0 || depth == 1 && !separated) fail("Unmatched or uncached field end")
                                depth--
                                if (depth == 0) {
                                    val field = simple(code.toString(), cached)
                                    if (multi) {
                                        if (instruction(code.toString(), fail).kind != "TOC") fail("Only contents fields may span paragraphs")
                                        val start = requireNotNull(beginning)
                                        if (start.elements().any { !local(it, "pPr") && !local(it, "fldSimple") }) fail("Contents must occupy standalone paragraphs")
                                        start.children += field
                                    } else output.addAll(field)
                                }
                            }
                            else -> fail("Unknown field control")
                        }
                    } else if (depth != 0) {
                        if (depth == 1 && !separated) {
                            if (!local(node, "r") || node.elements().any { !local(it, "rPr") && !local(it, "instrText") }) fail("Invalid field instruction run")
                            for (text in node.elements().filter { local(it, "instrText") }) {
                                if (text.elements().isNotEmpty()) fail("Invalid field instruction text")
                                code.append(text.children.filterIsInstance<XmlText>().joinToString("") { it.text })
                                if (code.length > 2_048) fail("Field instruction budget exceeded")
                            }
                        } else if (depth == 1) {
                            if (local(node, "r") || local(node, "bookmarkStart") || local(node, "bookmarkEnd")) cached += node
                            else if (instruction(code.toString(), fail).kind != "TOC") fail("Unsupported field-result structure")
                        }
                    } else output += node
                }
                paragraph.children.clear(); paragraph.children.addAll(output)
                if (multi && beginning !== paragraph) {
                    if (paragraph.elements().any { !local(it, "pPr") }) fail("Text after multi-paragraph contents requires a separate paragraph")
                    if (paragraph.elements().any { local(it, "pPr") && it.elements().any { p -> local(p, "sectPr") } }) fail("Contents crosses a section boundary")
                    container.children.remove(paragraph)
                }
                if (depth == 0) { multi = false; beginning = null }
            }
            if (depth != 0) fail("Unclosed complex field")
        }
    }
}
