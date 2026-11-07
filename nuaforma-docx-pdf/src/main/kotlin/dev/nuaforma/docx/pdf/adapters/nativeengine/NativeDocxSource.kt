package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.DocumentSourceContext
import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText

/** Identity mapping survives metadata normalization; coordinates refer to the actual conversion copy. */
internal class NativeDocxSource(private val context: GenerationContext) {
    private data class Entry(val part: String, val parent: XmlElement?, val ordinal: Int, val paragraph: Int?, val anchor: XmlElement)
    private val entries = java.util.IdentityHashMap<XmlElement, Entry>()
    private val origins = java.util.IdentityHashMap<XmlElement, XmlElement>()
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    var current: XmlElement? = null

    fun alias(copy: XmlElement, original: XmlElement) {
        origins[copy] = original
    }

    fun index(root: XmlElement, part: String) {
        if (context.logging.mode != dev.nuaforma.contracts.LoggingMode.DEVELOPMENT) return
        var paragraph = 0
        fun visit(node: XmlElement, parent: XmlElement?, ordinal: Int, owner: Int?, anchor: XmlElement) {
            context.checkActive()
            val isParagraph = node.name.namespaceURI == word && node.name.localPart == "p"
            val number = if (isParagraph) ++paragraph else owner
            val source = if (isParagraph || node.name.localPart == "style") node else anchor
            entries[node] = Entry(part, parent, ordinal, number, source)
            val counts = mutableMapOf<javax.xml.namespace.QName, Int>()
            node.elements().forEach { child ->
                val ordinal = counts.merge(child.name, 1, Int::plus)!!
                visit(child, node, ordinal, number, source)
            }
        }
        visit(root, null, 1, null, root)
    }

    fun failure(message: String, fallbackPart: String): NativeDocxReadException {
        val node = origins[current] ?: current
        val entry = entries[node]
        val excerptNode = entry?.let { if (it.paragraph != null) it.anchor else node }
        val excerpt = excerptNode?.descendants()?.filter { it.name.namespaceURI == word && it.name.localPart == "t" }
            ?.flatMap { it.children.asSequence().filterIsInstance<XmlText>() }?.map { it.text }?.take(256)?.joinToString("")?.take(256).orEmpty()
        val explanation = if (message == "Unsupported document feature") when (node?.name?.localPart) {
            "attachedTemplate" -> "Cleanup detaches the Word template reference; its target is not fetched"
            "pBdr" -> "Paragraph borders are not supported by the native DOCX profile"
            "sdt" -> "This Word content control is not supported by the native DOCX profile"
            "ins", "del", "moveFrom", "moveTo" -> "Tracked changes are not supported by the native DOCX profile"
            else -> message
        } else message
        return NativeDocxReadException(explanation, entry?.part ?: fallbackPart,
            entry?.let { details(explanation, requireNotNull(node), excerpt) },
            entry?.paragraph?.let { "paragraph:$it" }, node?.let { "xml:${it.sourceLine}:${it.sourceColumn}" }).also {
                if (entry != null) it.sourceRoute = route(requireNotNull(node))
            }
    }

    private fun details(explanation: String, node: XmlElement, excerpt: String): DocumentSourceContext = try {
        DocumentSourceContext(explanation, path(node), label(node), excerpt)
    } catch (_: IllegalArgumentException) {
        DocumentSourceContext(explanation, null, null, excerpt)
    }

    private fun route(node: XmlElement): List<Pair<javax.xml.namespace.QName, Int>> {
        val result = mutableListOf<Pair<javax.xml.namespace.QName, Int>>()
        var current: XmlElement? = node
        while (current != null) {
            val entry = entries[current] ?: break
            result += current.name to entry.ordinal
            current = entry.parent
        }
        return result.asReversed()
    }

    private fun path(node: XmlElement): String {
        val segments = mutableListOf<String>()
        var current: XmlElement? = node
        while (current != null) {
            val entry = entries[current] ?: break
            segments.add("${label(current)}[${entry.ordinal}]")
            current = entry.parent
        }
        return segments.asReversed().joinToString("/", prefix = "/")
    }

    private fun label(node: XmlElement): String {
        val prefix = if (node.name.namespaceURI == word) "w" else "x"
        return "$prefix:${node.name.localPart}"
    }
}
