package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.xml.namespace.QName

/** Bounded non-printing review data. It never resolves fonts, evaluates text or contacts an author. */
internal class WordReviewMetadata(private val context: GenerationContext,
    private val failure: (String, XmlElement, String) -> NativeDocxReadException) {
    data class Part(val file: String, val namespace: String, val root: String, val relationship: String) {
        val path: String get() = "word/$file.xml"
        val contentType: String get() = "application/vnd.openxmlformats-officedocument.wordprocessingml.$file+xml"
    }

    companion object {
        const val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        const val w14 = "http://schemas.microsoft.com/office/word/2010/wordml"
        const val w15 = "http://schemas.microsoft.com/office/word/2012/wordml"
        const val cid = "http://schemas.microsoft.com/office/word/2016/wordml/cid"
        const val cex = "http://schemas.microsoft.com/office/word/2018/wordml/cex"
        private const val office = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        private const val mc = "http://schemas.openxmlformats.org/markup-compatibility/2006"
        val definitions = listOf(
            Part("comments", word, "comments", "$office/comments"),
            Part("commentsExtended", w15, "commentsEx", "http://schemas.microsoft.com/office/2011/relationships/commentsExtended"),
            Part("commentsIds", cid, "commentsIds", "http://schemas.microsoft.com/office/2016/09/relationships/commentsIds"),
            Part("commentsExtensible", cex, "commentsExtensible", "http://schemas.microsoft.com/office/2018/08/relationships/commentsExtensible"),
            Part("people", w15, "people", "http://schemas.microsoft.com/office/2011/relationships/people"))
        private val toggles = setOf("b", "i", "strike", "dstrike", "noProof", "vanish", "webHidden", "rtl", "bCs", "iCs", "cs")
        private val basicProperties = mapOf(
            "pStyle" to setOf("val"), "rStyle" to setOf("val"), "sz" to setOf("val"), "szCs" to setOf("val"),
            "vertAlign" to setOf("val"), "lang" to setOf("val", "bidi", "eastAsia"),
            "color" to setOf("val", "themeColor", "themeTint", "themeShade"),
            "u" to setOf("val", "color", "themeColor", "themeTint", "themeShade"),
            "rFonts" to setOf("ascii", "hAnsi", "cs", "eastAsia", "asciiTheme", "hAnsiTheme", "cstheme", "eastAsiaTheme", "hint"))
    }

    private fun check(condition: Boolean, part: String, node: XmlElement, message: String) {
        context.checkActive()
        if (!condition) throw failure(part, node, message)
    }

    private fun whitespace(node: XmlElement) = node.children.filterIsInstance<XmlText>().all { it.text.isBlank() }

    private fun attributes(node: XmlElement, namespace: String, names: Set<String>, part: String) {
        check(node.attributes.all { (name, value) -> name.namespaceURI == namespace && name.localPart in names &&
            value.length <= 512 && value.none(Char::isISOControl) }, part, node, "Invalid review metadata attributes")
    }

    private fun root(node: XmlElement, definition: Part) {
        check(node.name == QName(definition.namespace, definition.root) && whitespace(node), definition.path, node, "Invalid review metadata root")
        check(node.attributes.keys.all { it == QName(mc, "Ignorable") }, definition.path, node, "Unsupported review metadata root attribute")
        node.attribute(mc, "Ignorable")?.let { value ->
            check(value.isNotBlank() && value.length <= 512 && value.trim().split(Regex("\\s+")).all { it in node.namespaces },
                definition.path, node, "Unbound review compatibility prefix")
        }
        check(node.elements().size <= 1_000, definition.path, node, "Review metadata entry budget exceeded")
    }

    private fun hex(node: XmlElement, namespace: String, name: String, part: String): String {
        val value = node.attribute(namespace, name)
        check(value != null && value.matches(Regex("[A-Fa-f0-9]{8}")), part, node, "Invalid review metadata identity")
        return requireNotNull(value).uppercase(java.util.Locale.ROOT)
    }

    private fun date(node: XmlElement, namespace: String, name: String, part: String, utc: Boolean = false) {
        val value = node.attribute(namespace, name) ?: return
        check(value.length <= 64 && (!utc || value.endsWith("Z") || value.endsWith("+00:00")), part, node, "Invalid review timestamp")
        try { DateTimeFormatter.ISO_DATE_TIME.parse(value) }
        catch (_: DateTimeParseException) { throw failure(part, node, "Invalid review timestamp") }
    }

    private fun toggle(node: XmlElement, namespace: String, name: String, part: String) {
        check(node.attribute(namespace, name) in setOf(null, "0", "1", "false", "true", "off", "on"),
            part, node, "Invalid review metadata toggle")
    }

    fun validate(parts: Map<String, XmlElement>): Set<String> {
        val commentIds = linkedSetOf<String>()
        for (definition in definitions) parts[definition.path]?.let { node ->
            root(node, definition)
            if (definition.file == "comments") commentIds += comments(node, definition.path)
            else if (definition.file == "people") people(node, definition)
            else identities(node, definition)
        }
        return commentIds
    }

    private fun comments(root: XmlElement, part: String): Set<String> {
        val ids = linkedSetOf<String>()
        val budget = CommentBudget()
        for (comment in root.elements()) {
            val id = commentIdentity(comment, part)
            check(ids.add(id), part, comment, "Duplicate comment identity")
            comment.elements().forEach { paragraph -> paragraph.descendants().forEach { commentNode(it, part, budget) } }
        }
        return ids
    }

    private data class CommentBudget(var nodes: Int = 0, var text: Long = 0)

    private fun commentIdentity(comment: XmlElement, part: String): String {
        check(comment.name == QName(word, "comment") && whitespace(comment), part, comment, "Invalid review comment")
        attributes(comment, word, setOf("id", "author", "date", "initials"), part)
        val id = comment.attribute(word, "id")?.toLongOrNull()?.takeIf { it in 0..2_147_483_647L }?.toString()
        check(id != null, part, comment, "Invalid comment identity")
        check(!comment.attribute(word, "author").isNullOrBlank(), part, comment, "Missing comment author")
        date(comment, word, "date", part)
        check(comment.elements().isNotEmpty(), part, comment, "A text comment requires a paragraph")
        for (child in comment.elements()) check(child.name == QName(word, "p"), part, child, "Only text comments are qualified")
        return requireNotNull(id)
    }

    private fun commentNode(node: XmlElement, part: String, budget: CommentBudget) {
        check(++budget.nodes <= 20_000 && node.name.namespaceURI == word, part, node, "Unsupported or oversized comment content")
        hierarchy(node, part)
        val name = node.name.localPart
        when (name) {
            "p", "r" -> bookkeeping(node, part)
            "pPr", "rPr", "annotationRef", "tab" -> attributes(node, word, emptySet(), part)
            "t" -> text(node, part, budget)
            in toggles -> { attributes(node, word, setOf("val"), part); toggle(node, word, "val", part) }
            in basicProperties -> attributes(node, word, basicProperties.getValue(name), part)
            else -> throw failure(part, node, "Unsupported active or complex comment content")
        }
        if (name != "t") check(whitespace(node), part, node, "Invalid mixed comment content")
    }

    private fun hierarchy(node: XmlElement, part: String) {
        val allowed = when (node.name.localPart) {
            "p" -> setOf("pPr", "r")
            "r" -> setOf("rPr", "t", "annotationRef", "tab")
            "pPr" -> setOf("pStyle")
            "rPr" -> basicProperties.keys + toggles
            else -> emptySet()
        }
        for (child in node.elements()) check(child.name.namespaceURI == word && child.name.localPart in allowed,
            part, child, "Unsupported comment content hierarchy")
    }

    private fun bookkeeping(node: XmlElement, part: String) {
        check(node.attributes.keys.all { it.namespaceURI == word && it.localPart in setOf("rsidR", "rsidRPr", "rsidRDefault", "rsidP", "rsidDel") ||
            node.name.localPart == "p" && it.namespaceURI == w14 && it.localPart in setOf("paraId", "textId") }, part, node, "Unsupported comment content attribute")
        check(node.attributes.values.all { it.matches(Regex("[A-Fa-f0-9]{8}")) }, part, node, "Invalid comment bookkeeping identity")
    }

    private fun text(node: XmlElement, part: String, budget: CommentBudget) {
        val xml = "http://www.w3.org/XML/1998/namespace"
        attributes(node, xml, setOf("space"), part)
        check(node.attribute(xml, "space") in setOf(null, "default", "preserve"), part, node, "Invalid comment text node")
        budget.text += node.children.filterIsInstance<XmlText>().sumOf { it.text.length.toLong() }
        check(budget.text <= 1_048_576, part, node, "Comment text budget exceeded")
    }

    private fun identities(root: XmlElement, definition: Part) {
        val spec = when (definition.file) {
            "commentsExtended" -> "commentEx" to setOf("paraId", "paraIdParent", "done")
            "commentsIds" -> "commentId" to setOf("paraId", "durableId")
            else -> "commentExtensible" to setOf("durableId", "dateUtc", "intelligentPlaceholder")
        }
        val seen = mutableMapOf<String, MutableSet<String>>()
        for (node in root.elements()) {
            check(node.name == QName(definition.namespace, spec.first) && whitespace(node) && node.elements().isEmpty(), definition.path, node, "Unsupported review identity payload")
            attributes(node, definition.namespace, spec.second, definition.path)
            for (name in listOf("paraId", "durableId").filter { it in spec.second }) {
                val value = hex(node, definition.namespace, name, definition.path)
                check(seen.getOrPut(name) { mutableSetOf() }.add(value), definition.path, node, "Duplicate review identity")
            }
            node.attribute(definition.namespace, "paraIdParent")?.let { hex(node, definition.namespace, "paraIdParent", definition.path) }
            toggle(node, definition.namespace, "done", definition.path)
            toggle(node, definition.namespace, "intelligentPlaceholder", definition.path)
            date(node, definition.namespace, "dateUtc", definition.path, utc = true)
        }
        // Well-formed unused modern caches can remain after all actual comments were deleted in Word.
    }

    private fun people(root: XmlElement, definition: Part) {
        for (person in root.elements()) {
            check(person.name == QName(w15, "person") && whitespace(person), definition.path, person, "Invalid review contact metadata")
            attributes(person, w15, setOf("author"), definition.path)
            check(!person.attribute(w15, "author").isNullOrBlank() && person.elements().size <= 1, definition.path, person, "Invalid review contact author")
            for (presence in person.elements()) {
                check(presence.name == QName(w15, "presenceInfo") && presence.elements().isEmpty() && whitespace(presence), definition.path, presence, "Unsupported review contact payload")
                attributes(presence, w15, setOf("providerId", "userId"), definition.path)
                check(!presence.attribute(w15, "providerId").isNullOrBlank() && !presence.attribute(w15, "userId").isNullOrBlank(), definition.path, presence, "Missing review contact identity")
            }
        }
    }

}
