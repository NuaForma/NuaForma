package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText

/** Unbound custom XML remains in the source; its data and schemas never become executable or print content. */
internal class WordCustomXmlMetadata(private val context: GenerationContext) {
    private val office = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private val relations = "http://schemas.openxmlformats.org/package/2006/relationships"
    private val custom = "http://schemas.openxmlformats.org/officeDocument/2006/customXml"
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val contentTypes = "http://schemas.openxmlformats.org/package/2006/content-types"
    private val bibliography = "http://schemas.openxmlformats.org/officeDocument/2006/bibliography"
    private val dataName = Regex("(?i:customXml)/item[1-9][0-9]?\\.xml")

    /** Validate the untouched graph before the owning serializer can discard malformed relationships. */
    fun validateSource(bytes: Map<String, ByteArray>) {
        if (bytes.keys.none(dataName::matches)) return
        val xml = ConversionXml(context)
        val parsed = bytes.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }
            .mapValues { (part, value) -> xml.readPart(value, part) }.toMutableMap()
        val source = NativeDocxSource(context)
        for ((part, root) in parsed) source.index(root, part)
        removeFromPrintModel(parsed) { part, node, message ->
            source.current = node
            source.failure(message, part)
        }
    }

    fun removeFromPrintModel(parts: MutableMap<String, XmlElement>, failure: (String, XmlElement, String) -> NativeDocxReadException) {
        val selected = parts.filter { (part, root) -> dataName.matches(part) && !belongsToBibliography(part, root, parts) }
        if (selected.isEmpty()) {
            noncanonicalRemainder(parts, emptySet(), failure)
            return
        }

        fun valid(condition: Boolean, part: String, node: XmlElement) {
            if (!condition) throw failure(part, node, "Unsupported or invalid passive custom XML metadata")
        }

        for ((part, root) in parts) for (node in root.descendants()) {
            context.checkActive()
            if (node.name.namespaceURI == word && node.name.localPart == "dataBinding")
                throw failure(part, node, "Active custom XML data bindings are not qualified")
        }
        val mainPart = "word/_rels/document.xml.rels"
        val main = parts[mainPart] ?: throw failure(mainPart, selected.values.first(), "Custom XML data requires document relationships")
        valid(main.name.namespaceURI == relations && main.name.localPart == "Relationships" && main.attributes.isEmpty() && whitespace(main), mainPart, main)
        val ids = main.elements().map { it.attribute("", "Id") }
        valid(ids.all { !it.isNullOrBlank() } && ids.distinct().size == ids.size, mainPart, main)
        val removed = mutableSetOf<String>()
        val links = mutableSetOf<XmlElement>()
        for ((part, root) in selected) {
            context.checkActive()
            val references = main.elements().filter { it.attribute("", "Target") == "../$part" }
            valid(references.size == 1, part, root)
            val link = references.single()
            relationship(link, "customXml", "../$part", mainPart, ::valid)
            links += link
            removed += part
            data(parts, part, root, ::valid)
            sidecar(parts, part, removed, ::valid, failure)
        }
        noncanonicalRemainder(parts, removed, failure)
        // Commit the private-model change only after all selected graphs passed admission.
        main.children.removeAll(links)
        removed.forEach(parts::remove)
    }

    private fun belongsToBibliography(part: String, root: XmlElement, parts: Map<String, XmlElement>): Boolean {
        if (root.name.namespaceURI == bibliography) return true
        val folder = part.substringBeforeLast('/')
        val link = parts["$folder/_rels/${part.substringAfterLast('/')}.rels"]?.elements()?.singleOrNull() ?: return false
        val target = link.attribute("", "Target") ?: return false
        if (!target.matches(Regex("itemProps[1-9][0-9]?\\.xml"))) return false
        val properties = parts["$folder/$target"] ?: return false
        return properties.descendants().any { node ->
            context.checkActive()
            node.name.namespaceURI == custom && node.name.localPart == "schemaRef" && node.attribute(custom, "uri") == bibliography
        }
    }

    private fun data(parts: Map<String, XmlElement>, part: String, root: XmlElement,
        valid: (Boolean, String, XmlElement) -> Unit) {
        var count = 0
        for (node in root.descendants()) {
            context.checkActive()
            valid(++count <= 16_384 && node.name.namespaceURI !in setOf(word,
                "http://schemas.openxmlformats.org/drawingml/2006/main",
                "http://www.w3.org/1999/XSL/Transform"), part, node)
        }
        requireType(parts, part, "application/xml", root, valid)
    }

    private fun sidecar(parts: Map<String, XmlElement>, part: String, removed: MutableSet<String>,
        valid: (Boolean, String, XmlElement) -> Unit, failure: (String, XmlElement, String) -> NativeDocxReadException) {
        val folder = part.substringBeforeLast('/')
        val relPart = "$folder/_rels/${part.substringAfterLast('/')}.rels"
        val tree = parts[relPart] ?: return
        valid(removed.add(relPart) && tree.name.namespaceURI == relations && tree.name.localPart == "Relationships" &&
            tree.attributes.isEmpty() && whitespace(tree) && tree.elements().size == 1, relPart, tree)
        val propertyLink = tree.elements().single()
        val target = propertyLink.attribute("", "Target").orEmpty()
        valid(target.matches(Regex("itemProps[1-9][0-9]?\\.xml")), relPart, propertyLink)
        relationship(propertyLink, "customXmlProps", target, relPart, valid)
        val propsPart = "$folder/$target"
        val props = parts[propsPart] ?: throw failure(propsPart, propertyLink, "Missing custom XML datastore properties")
        valid(removed.add(propsPart), propsPart, props)
        requireType(parts, propsPart, "application/vnd.openxmlformats-officedocument.customXmlProperties+xml", props, valid)
        properties(props, propsPart, valid)
    }

    private fun noncanonicalRemainder(parts: Map<String, XmlElement>, removed: Set<String>,
        failure: (String, XmlElement, String) -> NativeDocxReadException) {
        parts.entries.firstOrNull { (part, _) -> part.startsWith("customXml/", true) && !part.startsWith("customXml/") && part !in removed }?.let { (part, node) ->
            throw failure(part, node, "Unowned or unqualified custom XML package naming")
        }
    }

    private fun requireType(parts: Map<String, XmlElement>, part: String, expected: String, node: XmlElement,
        valid: (Boolean, String, XmlElement) -> Unit) {
        val name = "[Content_Types].xml"
        val types = parts[name]
        valid(types != null && types.name.namespaceURI == contentTypes && types.name.localPart == "Types", name, types ?: node)
        val declarations = requireNotNull(types).elements()
        val overrides = declarations.filter { it.name.namespaceURI == contentTypes && it.name.localPart == "Override" && it.attribute("", "PartName") == "/$part" }
        valid(overrides.size <= 1, name, overrides.lastOrNull() ?: types)
        val entries = if (overrides.isNotEmpty()) overrides else declarations.filter {
            it.name.namespaceURI == contentTypes && it.name.localPart == "Default" && it.attribute("", "Extension") == "xml"
        }
        valid(entries.size == 1 && entries.single().attribute("", "ContentType") == expected, name, entries.firstOrNull() ?: types)
    }

    private fun relationship(node: XmlElement, type: String, target: String, part: String,
        valid: (Boolean, String, XmlElement) -> Unit) {
        valid(node.name.namespaceURI == relations && node.name.localPart == "Relationship" && node.elements().isEmpty() && whitespace(node) &&
            node.attributes.keys.all { it.namespaceURI.isEmpty() && it.localPart in setOf("Id", "Type", "Target", "TargetMode") } &&
            !node.attribute("", "Id").isNullOrBlank() && node.attribute("", "Type") == "$office/$type" &&
            node.attribute("", "Target") == target && node.attribute("", "TargetMode") in setOf(null, "Internal"), part, node)
    }

    private fun properties(root: XmlElement, part: String, valid: (Boolean, String, XmlElement) -> Unit) {
        valid(root.name.namespaceURI == custom && root.name.localPart == "datastoreItem" && whitespace(root) &&
            root.attributes.keys.all { it.namespaceURI == custom && it.localPart == "itemID" } &&
            root.attribute(custom, "itemID")?.matches(Regex("\\{[A-Fa-f0-9]{8}(?:-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}\\}")) == true &&
            root.elements().size <= 1, part, root)
        for (container in root.elements()) {
            valid(container.name.namespaceURI == custom && container.name.localPart == "schemaRefs" &&
                container.attributes.isEmpty() && whitespace(container) && container.elements().size <= 64, part, container)
            for (reference in container.elements()) {
                context.checkActive()
                val uri = reference.attribute(custom, "uri")
                valid(reference.name.namespaceURI == custom && reference.name.localPart == "schemaRef" && reference.elements().isEmpty() && whitespace(reference) &&
                    reference.attributes.keys.all { it.namespaceURI == custom && it.localPart == "uri" } &&
                    uri != null && uri.isNotBlank() && uri.length <= 4096 && uri.none(Char::isISOControl), part, reference)
            }
        }
    }

    private fun whitespace(node: XmlElement) = node.children.filterIsInstance<XmlText>().all { it.text.isBlank() }

}
