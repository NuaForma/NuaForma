package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.*
import javax.xml.namespace.QName

/** Materialize qualified property paragraphs while retaining original formatting sources. */
internal class NativePropertyParagraphs(
    private val context: GenerationContext,
    private val values: NativeWordValues,
    private val focus: (XmlElement) -> Unit,
    private val alias: (XmlElement, XmlElement) -> Unit,
    private val reject: (String) -> Nothing,
) {
    data class Sample(val paragraph: XmlElement, val run: XmlElement)
    class Format(val samples: List<Sample>)
    val formats = java.util.IdentityHashMap<XmlElement, Format>()
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private data class Position(val paragraph: Int, val child: Int)
    private data class Range(val start: Position, val separator: Position, val end: Position, val code: String)

    fun normalize(root: XmlElement) {
        if (root.name.namespaceURI != word || root.name.localPart !in setOf("document", "hdr", "ftr", "footnotes", "endnotes", "body")) return
        for (container in root.descendants().filter { it.name.namespaceURI == word && it.name.localPart in setOf("body", "tc", "hdr", "ftr", "txbxContent", "footnote", "endnote") }.toList()) {
            val blocks = container.elements()
            val ranges = ranges(blocks)
            for (range in ranges.asReversed()) expand(container, blocks, range)
        }
    }

    private fun ranges(blocks: List<XmlElement>): List<Range> {
        val result = mutableListOf<Range>()
        var start: Position? = null
        var separator: Position? = null
        var depth = 0
        var nested = false
        val code = StringBuilder()
        for ((p, paragraph) in blocks.withIndex()) {
            context.checkActive()
            if (!isWord(paragraph, "p")) {
                if (depth != 0) reject("Field crosses a structural boundary")
                continue
            }
            for ((i, node) in paragraph.elements().filterNot { isWord(it, "pPr") }.withIndex()) {
                context.checkActive()
                focus(node)
                if (isWord(node, "fldSimple") && depth == 0) {
                    val instruction = NativeDocxFields.instruction(node.attribute(word, "instr") ?: reject("Missing field instruction"), reject)
                    if (instruction.kind == "DOCPROPERTY" && values.propertyText(instruction).contains('\n')) result += Range(Position(p, i), Position(p, i), Position(p, i), node.attribute(word, "instr")!!)
                    continue
                }
                val marker = node.elements().singleOrNull { isWord(it, "fldChar") }?.takeIf { isWord(node, "r") }
                if (marker != null) {
                    when (marker.attribute(word, "fldCharType")) {
                        "begin" -> {
                            if (depth == 0) { start = Position(p, i); separator = null; nested = false; code.setLength(0) } else nested = true
                            depth++
                        }
                        "separate" -> if (depth == 1) {
                            if (separator != null) reject("Duplicate field separator")
                            separator = Position(p, i)
                        }
                        "end" -> {
                            if (depth == 0) reject("Unmatched field end")
                            depth--
                            if (depth == 0 && code.toString().trimStart().startsWith("DOCPROPERTY", true)) {
                                val instruction = NativeDocxFields.instruction(code.toString(), reject)
                                if (nested) reject("Nested document property fields are not qualified")
                                val begin = requireNotNull(start)
                                val separated = separator ?: reject("Missing property field separator")
                                val origin = blocks[begin.paragraph].elements().filterNot { isWord(it, "pPr") }[begin.child]
                                focus(origin.elements().single { isWord(it, "fldChar") })
                                if (p != begin.paragraph || values.propertyText(instruction).contains('\n')) result += Range(begin, separated, Position(p, i), code.toString())
                            }
                        }
                        else -> reject("Invalid field control")
                    }
                } else if (depth == 1 && separator == null && !isWord(node, "pPr")) {
                    if (!isWord(node, "r") || node.elements().any { !isWord(it, "rPr") && !isWord(it, "instrText") }) reject("Invalid field instruction run")
                    for (text in node.elements().filter { isWord(it, "instrText") }) {
                        if (text.elements().isNotEmpty()) reject("Invalid field instruction text")
                        code.append(text.children.filterIsInstance<XmlText>().joinToString("") { it.text })
                        if (code.length > 2_048) reject("Field instruction budget exceeded")
                    }
                }
            }
        }
        return result
    }

    private fun expand(container: XmlElement, blocks: List<XmlElement>, range: Range) {
        val originals = blocks.subList(range.start.paragraph, range.end.paragraph + 1)
        if (originals.size > 64 || originals.any { it.elements().any { p -> isWord(p, "pPr") && p.descendants().any { isWord(it, "sectPr") || isWord(it, "numPr") } } }) reject("Property field crosses section or numbering ownership")
        val first = originals.first()
        val last = originals.last()
        val children = originals.map { paragraph -> paragraph.elements().filterNot { isWord(it, "pPr") } }
        val origin = children.first()[range.start.child]
        focus(origin)
        val simple = isWord(origin, "fldSimple")
        if (simple) {
            if (origin.attributes.keys.any { it.namespaceURI != word || it.localPart !in setOf("instr", "dirty", "fldLock") } || origin.attribute(word, "fldLock") !in setOf(null, "0", "false", "off")) reject("Unsupported property field state")
            origin.attribute(word, "dirty")?.let { if (it !in setOf("0", "1", "true", "false", "on", "off")) reject("Invalid field state") }
        } else for (p in originals.indices) {
            val low = if (p == 0) range.start.child else 0
            val high = if (p == originals.lastIndex) range.end.child else children[p].lastIndex
            for (node in children[p].subList(low, high + 1)) node.elements().singleOrNull { isWord(it, "fldChar") }?.let { marker ->
                if (node.elements().any { it !== marker && !isWord(it, "rPr") } || node.elements().count { isWord(it, "rPr") } > 1 || marker.elements().isNotEmpty() ||
                    marker.attributes.keys.any { it.namespaceURI != word || it.localPart !in setOf("fldCharType", "dirty", "fldLock") } || marker.attribute(word, "fldLock") !in setOf(null, "0", "false", "off")) reject("Unsupported property field control")
                marker.attribute(word, "dirty")?.let { if (it !in setOf("0", "1", "true", "false", "on", "off")) reject("Invalid field state") }
            }
        }
        val samples = mutableListOf<Sample>()
        val starts = mutableListOf<XmlElement>()
        val ends = mutableListOf<XmlElement>()
        for ((p, paragraph) in originals.withIndex()) {
            val cache = if (simple) origin.elements() else {
                val absolute = range.start.paragraph + p
                if (absolute < range.separator.paragraph) emptyList() else {
                    val low = if (absolute == range.separator.paragraph) range.separator.child + 1 else 0
                    val high = if (p == originals.lastIndex) range.end.child else children[p].size
                    children[p].subList(low, high)
                }
            }
            for (node in cache) {
                context.checkActive()
                focus(node)
                when {
                    isWord(node, "r") -> {
                        if (node.attributes.isNotEmpty() || node.elements().any { !isWord(it, "rPr") && !isWord(it, "t") } || node.elements().count { isWord(it, "rPr") } > 1) reject("Unsupported property result content")
                        for (text in node.elements().filter { isWord(it, "t") }) if (text.elements().isNotEmpty() || text.attributes.keys.any { it.namespaceURI != "http://www.w3.org/XML/1998/namespace" || it.localPart != "space" }) reject("Invalid property result text")
                        samples += Sample(paragraph, node)
                    }
                    isWord(node, "bookmarkStart") -> { if (samples.isNotEmpty()) reject("Partial property result bookmarks are not qualified"); starts += node }
                    isWord(node, "bookmarkEnd") -> ends += node
                    else -> reject("Unsupported property result structure")
                }
                if (samples.size > 1_024) reject("Property formatting sample budget exceeded")
                if (ends.isNotEmpty() && isWord(node, "r")) reject("Partial property result bookmarks are not qualified")
            }
        }
        val instruction = NativeDocxFields.instruction(range.code, reject)
        focus(origin)
        val value = values.propertyText(instruction)
        val lines = value.split('\n')
        if (lines.size > 64) reject("Property paragraph budget exceeded")
        values.consumePropertyText(value)
        val format = Format(samples.ifEmpty { listOf(Sample(first, origin)) })
        val prefix = children.first().take(range.start.child)
        val suffix = children.last().drop(range.end.child + 1)
        val output = lines.mapIndexed { index, line ->
            context.checkActive()
            val owner = if (index == lines.lastIndex) last else first
            val paragraph = XmlElement(QName(word, "p", "w"))
            alias(paragraph, owner)
            val properties = if (index == lines.lastIndex) last else if (index < 2) first else null
            properties?.elements()?.singleOrNull { isWord(it, "pPr") }?.let { paragraph.children += clone(it) }
            if (index == 0) { paragraph.children.addAll(prefix); paragraph.children.addAll(starts) }
            if (line.isNotEmpty()) {
                val run = XmlElement(QName(word, "r", "w"))
                format.samples.first().run.elements().singleOrNull { isWord(it, "rPr") }?.let { run.children += clone(it) }
                val text = XmlElement(QName(word, "t", "w"), linkedMapOf(QName("http://www.w3.org/XML/1998/namespace", "space", "xml") to "preserve"), children = mutableListOf(XmlText(line)))
                run.children += text
                formats[run] = format
                alias(run, origin); alias(text, origin)
                paragraph.children += run
            }
            if (index == lines.lastIndex) { paragraph.children.addAll(ends); paragraph.children.addAll(suffix) }
            paragraph
        }
        val at = container.children.indexOf(first)
        val tail = container.children.indexOf(last)
        if (at < 0 || tail < at) reject("Overlapping property paragraph ranges are not qualified")
        container.children.subList(at, tail + 1).clear()
        container.children.addAll(at, output)
    }

    private fun clone(node: XmlElement): XmlElement = XmlElement(node.name, LinkedHashMap(node.attributes), LinkedHashMap(node.namespaces)).also { copy ->
        alias(copy, node)
        copy.children.addAll(node.children.map { if (it is XmlElement) clone(it) else it })
    }

    private fun isWord(node: XmlElement, name: String) = node.name.namespaceURI == word && node.name.localPart == name

}
