package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText

/** Office property-panel data is retained in the source, never interpreted as schemas or print content. */
internal class WordContentTypeMetadata(private val context: GenerationContext) {
    private val office = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private val relations = "http://schemas.openxmlformats.org/package/2006/relationships"
    private val custom = "http://schemas.openxmlformats.org/officeDocument/2006/customXml"
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val roots = mapOf(
        "http://schemas.microsoft.com/office/2006/metadata/contentType" to "contentTypeSchema",
        "http://schemas.microsoft.com/office/2006/metadata/properties" to "properties",
        "http://schemas.microsoft.com/sharepoint/v3/contenttype/forms" to "FormTemplates")

    fun removeFromPrintModel(parts: MutableMap<String, XmlElement>, failure: (String, XmlElement, String) -> NativeDocxReadException) {
        val selected = parts.filter { (part, root) -> part.matches(Regex("customXml/item[1-9][0-9]?\\.xml")) && roots[root.name.namespaceURI] == root.name.localPart }
        if (selected.isEmpty()) return

        fun valid(condition: Boolean, part: String, node: XmlElement) {
            if (!condition) throw failure(part, node, "Unsupported or invalid Office property metadata")
        }

        for ((part, root) in parts) for (node in root.descendants()) {
            context.checkActive()
            if (node.name.namespaceURI == word && node.name.localPart == "dataBinding")
                throw failure(part, node, "Active custom XML data bindings are not qualified")
        }
        val mainPart = "word/_rels/document.xml.rels"
        val main = parts[mainPart] ?: throw failure(mainPart, selected.values.first(), "Office metadata requires document relationships")
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
            var count = 0
            for (node in root.descendants()) {
                context.checkActive()
                valid(++count <= 16_384 && node.name.namespaceURI !in setOf(word,
                    "http://schemas.openxmlformats.org/drawingml/2006/main",
                    "http://www.w3.org/1999/XSL/Transform"), part, node)
            }
            val relPart = "customXml/_rels/${part.substringAfterLast('/')}.rels"
            val tree = parts[relPart] ?: continue
            valid(removed.add(relPart) && tree.name.namespaceURI == relations && tree.name.localPart == "Relationships" &&
                tree.attributes.isEmpty() && whitespace(tree) && tree.elements().size == 1, relPart, tree)
            val propertyLink = tree.elements().single()
            val target = propertyLink.attribute("", "Target").orEmpty()
            valid(target.matches(Regex("itemProps[1-9][0-9]?\\.xml")), relPart, propertyLink)
            relationship(propertyLink, "customXmlProps", target, relPart, ::valid)
            val propsPart = "customXml/$target"
            val props = parts[propsPart] ?: throw failure(propsPart, propertyLink, "Missing Office metadata datastore properties")
            valid(removed.add(propsPart), propsPart, props)
            properties(props, propsPart, ::valid)
        }
        // Commit the private-model change only after all selected graphs passed admission.
        main.children.removeAll(links)
        removed.forEach(parts::remove)
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
