package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText

/** Canonicalize nonprinting block boundaries without introducing layout paragraphs. */
internal class NativeDocxBookmarks(private val context: GenerationContext) {
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    /** Check original table-ending and editing ranges before the DOCX binder can discard malformed marker properties. */
    fun validateSource(root: XmlElement, focus: (XmlElement) -> Unit, reject: (String) -> Nothing) {
        val editing = root.descendants().filter { it.name.localPart == "bookmarkStart" && it.attribute(word, "name") == "_GoBack" }.toList()
        for (node in editing) {
            focus(node)
            if (!isWord(node, "bookmarkStart")) reject("Invalid editing bookmark namespace")
        }
        if (editing.isNotEmpty() || root.descendants().any { isWord(it, "tbl") && it.elements().any { child -> isWord(child, "bookmarkEnd") } }) {
            validate(root, focus, reject)
        }
    }

    /** Omit only unused empty Word editing-position ranges; keep every paragraph and visible bookmark. */
    fun normalizeEditing(roots: Collection<XmlElement>, focus: (XmlElement) -> Unit, reject: (String) -> Nothing) {
        val starts = roots.flatMap { root -> root.descendants().filter { isWord(it, "bookmarkStart") && it.attribute(word, "name") == "_GoBack" }.toList() }
        if (starts.isEmpty()) return
        if (starts.size > 1) { focus(starts[1]); reject("Duplicate editing bookmark name") }
        for (root in roots) {
            context.checkActive()
            validateSource(root, focus, reject)
        }
        if (roots.any(::referencesEditingBookmark)) return
        val start = starts.single()
        for (root in roots) for (parent in root.descendants().toList()) {
            context.checkActive()
            val elements = parent.elements()
            val index = elements.indexOfFirst { it === start }
            if (index < 0) continue
            if (!isWord(parent, "p") && !isWord(parent, "body")) return
            val end = elements.getOrNull(index + 1) ?: return
            if (!isWord(end, "bookmarkEnd") || end.attribute(word, "id") != start.attribute(word, "id")) return
            val between = parent.children.subList(parent.children.indexOf(start) + 1, parent.children.indexOf(end))
            if (between.filterIsInstance<XmlText>().any { it.text.isNotBlank() }) return
            parent.children.remove(start)
            parent.children.remove(end)
            return
        }
    }

    private fun referencesEditingBookmark(root: XmlElement): Boolean {
        val nodes = root.descendants().toList()
        if (nodes.any { isWord(it, "fldSimple") && it.attribute(word, "instr")?.contains("_GoBack", ignoreCase = true) == true ||
                isWord(it, "hyperlink") && it.attribute(word, "anchor")?.equals("_GoBack", ignoreCase = true) == true }) return true
        return nodes.filter { isWord(it, "instrText") }.joinToString("") { node ->
            node.children.filterIsInstance<XmlText>().joinToString("") { it.text }
        }.contains("_GoBack", ignoreCase = true)
    }

    /** Return block-ended ranges that require paragraph-aware text-reference resolution. */
    fun normalize(root: XmlElement, focus: (XmlElement) -> Unit, reject: (String) -> Nothing): Set<String> {
        val bodies = root.descendants().filter { isWord(it, "body") && it.elements().any(::bookmark) }.toList()
        if (bodies.isEmpty()) return emptySet()
        validate(root, focus, reject)
        val names = root.descendants().filter { isWord(it, "bookmarkStart") }
            .associate { it.attribute(word, "id") to it.attribute(word, "name") }
        val blockEndedRanges = bodies.flatMap { body -> body.elements().filter { isWord(it, "bookmarkEnd") } }
            .mapNotNull { names[it.attribute(word, "id")] }.toSet()
        for (body in bodies) normalizeBody(body, focus, reject)
        return blockEndedRanges
    }

    private fun validate(root: XmlElement, focus: (XmlElement) -> Unit, reject: (String) -> Nothing) {
        val starts = mutableMapOf<String, XmlElement>()
        val open = mutableSetOf<String>()
        for (node in root.descendants().filter(::bookmark)) {
            context.checkActive()
            focus(node)
            val start = isWord(node, "bookmarkStart")
            val allowed = if (start) setOf("id", "name") else setOf("id")
            if (node.attributes.keys.any { it.namespaceURI != word || it.localPart !in allowed } ||
                node.elements().isNotEmpty() || node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() }) reject("Invalid bookmark boundary")
            val id = node.attribute(word, "id")?.takeIf { it.matches(Regex("[0-9]{1,9}")) } ?: reject("Invalid bookmark ID")
            if (start) {
                if (starts.putIfAbsent(id, node) != null || starts.size > 1_000) reject("Duplicate or excessive bookmarks")
                open += id
            } else if (!open.remove(id)) reject("Unmatched bookmark end")
        }
        if (open.isNotEmpty()) { focus(starts.getValue(open.first())); reject("Unclosed bookmarks") }
    }

    private fun normalizeBody(body: XmlElement, focus: (XmlElement) -> Unit, reject: (String) -> Nothing) {
        val elements = body.elements()
        val section = elements.indexOfFirst { isWord(it, "sectPr") }
        if (section >= 0 && section != elements.lastIndex) { focus(elements[section + 1]); reject("Final section properties must end the body") }
        val prepend = java.util.IdentityHashMap<XmlElement, MutableList<XmlElement>>()
        val append = java.util.IdentityHashMap<XmlElement, MutableList<XmlElement>>()
        val anchors = java.util.IdentityHashMap<XmlElement, Pair<XmlElement?, XmlElement?>>()
        var previous: XmlElement? = null
        var cursor = 0
        while (cursor < elements.size) {
            context.checkActive()
            if (!bookmark(elements[cursor])) { previous = elements[cursor++]; continue }
            val group = mutableListOf<XmlElement>()
            while (cursor < elements.size && bookmark(elements[cursor])) group += elements[cursor++]
            val starts = group.filter { isWord(it, "bookmarkStart") }.map { it.attribute(word, "id") }.toSet()
            for (node in group) {
                focus(node)
                val forward = isWord(node, "bookmarkStart") || node.attribute(word, "id") in starts
                val block = (if (forward) elements.getOrNull(cursor) else previous)
                    ?: reject("Bookmark boundary requires an adjacent content block")
                if (!isWord(block, "p") && !isWord(block, "tbl")) reject("Bookmark boundary requires an adjacent content block")
                val pair = anchors.getOrPut(block) {
                    val paragraphs = block.descendants().filter { isWord(it, "p") }.toList()
                    paragraphs.firstOrNull() to paragraphs.lastOrNull()
                }
                val paragraph = (if (forward) pair.first else pair.second)
                    ?: reject("Bookmark boundary has no supported paragraph anchor")
                (if (forward) prepend else append).getOrPut(paragraph) { mutableListOf() }.add(node)
            }
        }
        body.children.removeAll { it is XmlElement && bookmark(it) }
        for ((paragraph, nodes) in prepend) {
            val properties = paragraph.elements().firstOrNull()?.takeIf { isWord(it, "pPr") }
            val at = properties?.let { paragraph.children.indexOf(it) + 1 } ?: 0
            paragraph.children.addAll(at, nodes)
        }
        for ((paragraph, nodes) in append) paragraph.children.addAll(nodes)
    }

    private fun bookmark(node: XmlElement) = isWord(node, "bookmarkStart") || isWord(node, "bookmarkEnd")

    private fun isWord(node: XmlElement, name: String) = node.name.namespaceURI == word && node.name.localPart == name

}
