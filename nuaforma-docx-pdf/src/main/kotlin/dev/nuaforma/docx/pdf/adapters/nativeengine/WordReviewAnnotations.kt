package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText
import javax.xml.namespace.QName
import java.util.IdentityHashMap

/** Validate and detach only known review annotations from a request-local clean print view. */
internal class WordReviewAnnotations(private val context: GenerationContext,
    private val failure: (String, XmlElement, String) -> NativeDocxReadException) {
    data class Change(val removed: Set<String> = emptySet(), val edited: Set<String> = emptySet())
    private data class Anchor(val part: String, val parent: XmlElement, val node: XmlElement, val position: Int, val container: XmlElement?)
    private val word = WordReviewMetadata.word
    private val relations = "http://schemas.openxmlformats.org/package/2006/relationships"
    private val types = "http://schemas.openxmlformats.org/package/2006/content-types"
    private val anchorNames = setOf("commentRangeStart", "commentRangeEnd", "commentReference")
    private val story = Regex("word/(?:document|footnotes|endnotes|(?:header|footer)[1-9][0-9]?)\\.xml")

    private fun check(condition: Boolean, part: String, node: XmlElement, detail: String) {
        context.checkActive()
        if (!condition) throw failure(part, node, detail)
    }

    private fun whitespace(node: XmlElement) = node.children.filterIsInstance<XmlText>().all { it.text.isBlank() }

    fun normalize(parts: MutableMap<String, XmlElement>): Change {
        val definitions = WordReviewMetadata.definitions.filter { it.path in parts }
        if (definitions.isEmpty()) return Change()
        val ids = WordReviewMetadata(context, failure).validate(parts)
        val removed = definitions.mapTo(linkedSetOf()) { it.path }
        val edited = linkedSetOf<String>()
        val anchors = anchors(parts, removed)
        validateAnchors(anchors, ids)
        val links = annotationLinks(parts, definitions)
        for (definition in definitions) emptyRelationships(parts, definition)?.let { removed += it }
        val declarations = declarations(parts, definitions) + auxiliaryDeclarations(parts, removed)
        // Only mutate after the full known annotation graph and all visible anchors have passed.
        for (anchor in anchors) {
            anchor.parent.children.remove(anchor.node)
            edited += anchor.part
        }
        removeReferenceOnlyRuns(anchors)
        for ((part, nodes) in links + declarations) {
            val root = requireNotNull(parts[part])
            root.children.removeAll(nodes.toSet())
            edited += part
        }
        removed.forEach { parts.remove(it) }
        return Change(removed, edited)
    }

    private fun anchors(parts: Map<String, XmlElement>, removed: Set<String>): List<Anchor> {
        val result = mutableListOf<Anchor>()
        for ((part, root) in parts.filterKeys { it !in removed && story.matches(it) }) {
            val parents = IdentityHashMap<XmlElement, XmlElement>()
            for (parent in root.descendants()) parent.elements().forEach { parents[it] = parent }
            for ((position, node) in root.descendants().withIndex()) {
                if (node.name.namespaceURI != word || node.name.localPart !in anchorNames) continue
                val parent = parents[node] ?: throw failure(part, node, "Invalid review anchor root")
                check(story.matches(part), part, node, "Review anchor outside a supported document story")
                check(node.attributes.keys.all { it == QName(word, "id") } && node.elements().isEmpty() && whitespace(node),
                    part, node, "Invalid review anchor structure")
                check(node.name.localPart != "commentReference" || parent.name == QName(word, "r"), part, node, "Comment reference requires a text run")
                if (node.name.localPart != "commentReference") check(parent.name.namespaceURI == word &&
                    parent.name.localPart in setOf("p", "hyperlink", "sdtContent", "smartTag", "body", "tc", "footnote", "endnote", "hdr", "ftr"),
                    part, node, "Unsupported review range location")
                result += Anchor(part, parent, node, position, parents[parent])
                check(result.size <= 3_000, part, node, "Review anchor budget exceeded")
            }
        }
        return result
    }

    private fun identity(anchor: Anchor): String {
        val value = anchor.node.attribute(word, "id")?.toLongOrNull()?.takeIf { it in 0..2_147_483_647L }
        check(value != null, anchor.part, anchor.node, "Invalid review anchor identity")
        return requireNotNull(value).toString()
    }

    private fun removeReferenceOnlyRuns(anchors: List<Anchor>) {
        for (anchor in anchors.filter { it.node.name.localPart == "commentReference" }) {
            val run = anchor.parent
            if (whitespace(run) && run.elements().all { it.name == QName(word, "rPr") })
                anchor.container?.children?.remove(run)
        }
    }

    private fun validateAnchors(anchors: List<Anchor>, ids: Set<String>) {
        for ((id, group) in anchors.groupBy(::identity)) {
            val first = group.first()
            check(id in ids, first.part, first.node, "Review anchor refers to a missing comment")
            val kinds = group.groupBy { it.node.name.localPart }
            check(kinds.values.all { it.size == 1 } && group.map { it.part }.distinct().size == 1,
                first.part, first.node, "Duplicate or cross-story review anchors")
            val start = kinds["commentRangeStart"]?.single()
            val end = kinds["commentRangeEnd"]?.single()
            check(kinds["commentReference"]?.size == 1 && (start == null) == (end == null),
                first.part, first.node, "Incomplete review anchor range")
            if (start != null && end != null) check(start.position < end.position, first.part, first.node, "Reversed review anchor range")
        }
    }

    private fun annotationLinks(parts: Map<String, XmlElement>, definitions: List<WordReviewMetadata.Part>): List<Pair<String, List<XmlElement>>> {
        val part = "word/_rels/document.xml.rels"
        val root = parts[part] ?: throw failure(definitions.first().path, requireNotNull(parts[definitions.first().path]), "Review part has no document relationship")
        check(root.name == QName(relations, "Relationships") && root.attributes.isEmpty() && whitespace(root), part, root, "Invalid review relationship root")
        val ids = root.elements().map { it.attribute("", "Id") }
        check(ids.all { !it.isNullOrBlank() } && ids.distinct().size == ids.size, part, root, "Invalid or duplicate document relationship identity")
        val selected = mutableListOf<XmlElement>()
        for (definition in definitions) {
            val links = root.elements().filter { it.attribute("", "Type") == definition.relationship }
            check(links.size == 1, part, root, "Missing or duplicate review relationship")
            val link = links.single()
            check(link.name == QName(relations, "Relationship") && link.elements().isEmpty() && whitespace(link) &&
                link.attributes.keys.all { it.namespaceURI.isEmpty() && it.localPart in setOf("Id", "Type", "Target", "TargetMode") } &&
                link.attribute("", "Target") == definition.file + ".xml" && link.attribute("", "TargetMode") in setOf(null, "Internal"),
                part, link, "External or invalid review relationship")
            selected += link
        }
        val known = WordReviewMetadata.definitions.map { it.relationship }.toSet()
        check(root.elements().filter { it.attribute("", "Type") in known }.size == selected.size,
            part, root, "Review relationship points to a missing metadata part")
        return listOf(part to selected)
    }

    private fun declarations(parts: Map<String, XmlElement>, definitions: List<WordReviewMetadata.Part>): List<Pair<String, List<XmlElement>>> {
        val part = "[Content_Types].xml"
        val root = parts[part] ?: throw failure(definitions.first().path, requireNotNull(parts[definitions.first().path]), "Missing review content types")
        check(root.name == QName(types, "Types") && root.attributes.isEmpty() && whitespace(root), part, root, "Invalid review content-type root")
        val selected = definitions.map { definition ->
            val nodes = root.elements().filter { it.attribute("", "PartName") == "/" + definition.path }
            check(nodes.size == 1, part, root, "Missing or duplicate review content-type declaration")
            nodes.single().also { node ->
                check(node.name == QName(types, "Override") && node.elements().isEmpty() && whitespace(node) &&
                    node.attributes.keys.all { it.namespaceURI.isEmpty() && it.localPart in setOf("PartName", "ContentType") } &&
                    node.attribute("", "ContentType") == definition.contentType, part, node, "Invalid review content-type declaration")
            }
        }
        return listOf(part to selected)
    }

    private fun emptyRelationships(parts: Map<String, XmlElement>, definition: WordReviewMetadata.Part): String? {
        val part = "word/_rels/" + definition.file + ".xml.rels"
        val root = parts[part] ?: return null
        check(root.name == QName(relations, "Relationships") && root.attributes.isEmpty() && root.elements().isEmpty() && whitespace(root),
            part, root, "Active comment dependencies are not qualified")
        return part
    }

    private fun auxiliaryDeclarations(parts: Map<String, XmlElement>, removed: Set<String>): List<Pair<String, List<XmlElement>>> {
        val part = "[Content_Types].xml"
        val root = requireNotNull(parts[part])
        val nodes = root.elements().filter { node -> removed.any { it.endsWith(".rels") && node.attribute("", "PartName") == "/$it" } }
        val names = nodes.map { it.attribute("", "PartName") }
        check(names.distinct().size == names.size, part, root, "Duplicate review relationship content types")
        for (node in nodes) check(node.name == QName(types, "Override") && node.elements().isEmpty() && whitespace(node) &&
            node.attributes.keys.all { it.namespaceURI.isEmpty() && it.localPart in setOf("PartName", "ContentType") } &&
            node.attribute("", "ContentType") == "application/vnd.openxmlformats-package.relationships+xml",
            part, node, "Invalid review relationship content type")
        return listOf(part to nodes)
    }

}
