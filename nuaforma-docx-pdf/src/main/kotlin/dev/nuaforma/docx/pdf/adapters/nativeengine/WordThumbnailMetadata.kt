package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText

/** OPC previews are opaque file-browser metadata, never decoded or used as printed images. */
internal class WordThumbnailMetadata(private val context: GenerationContext) {
    private val relations = "http://schemas.openxmlformats.org/package/2006/relationships"
    private val thumbnailType = "$relations/metadata/thumbnail"
    private val contentTypes = "http://schemas.openxmlformats.org/package/2006/content-types"

    fun normalize(parsed: MutableMap<String, XmlElement>, parts: Map<String, ByteArray>) {
        val names = parts.keys.filter { it.matches(Regex("docProps/thumbnail\\.(?:jpeg|jpg|png)")) }
        val root = parsed["_rels/.rels"] ?: return
        val links = root.elements().filter { it.attribute("", "Type") == thumbnailType }
        if (names.isEmpty() && links.isEmpty()) return

        fun valid(condition: Boolean, part: String = "_rels/.rels") {
            if (!condition) throw NativeDocxReadException("Invalid or unsupported package thumbnail metadata", part)
        }

        context.checkActive()
        if (links.isEmpty() && names.isNotEmpty())
            throw NativeDocxReadException("Orphan package thumbnail metadata", names.first())
        valid(names.size == 1 && links.size == 1)
        val name = names.single()
        val link = links.single()
        valid(root.name.namespaceURI == relations && root.name.localPart == "Relationships" &&
            root.attributes.isEmpty() && whitespace(root))
        val ids = root.elements().map { it.attribute("", "Id") }
        valid(ids.all { !it.isNullOrBlank() } && ids.distinct().size == ids.size)
        valid(link.name.namespaceURI == relations && link.name.localPart == "Relationship" &&
            link.elements().isEmpty() && whitespace(link) &&
            link.attributes.keys.all { it.namespaceURI.isEmpty() && it.localPart in setOf("Id", "Type", "Target", "TargetMode") } &&
            link.attribute("", "Target") == name && link.attribute("", "TargetMode") in setOf(null, "Internal"))
        valid(parts.getValue(name).isNotEmpty(), name)
        val types = parsed["[Content_Types].xml"] ?: throw NativeDocxReadException("Missing thumbnail content types", "[Content_Types].xml")
        valid(types.name.namespaceURI == contentTypes && types.name.localPart == "Types", "[Content_Types].xml")
        val overrides = types.elements().filter { it.attribute("", "PartName") == "/$name" }
        val defaults = types.elements().filter { it.attribute("", "Extension") == name.substringAfterLast('.') }
        valid(overrides.size <= 1 && defaults.size <= 1 && (overrides.isNotEmpty() || defaults.isNotEmpty()), "[Content_Types].xml")
        val declaration = (overrides.ifEmpty { defaults }).single()
        valid(declaration.name.namespaceURI == contentTypes && declaration.name.localPart == if (overrides.isEmpty()) "Default" else "Override", "[Content_Types].xml")
        valid(declaration.attribute("", "ContentType") == if (name.endsWith(".png")) "image/png" else "image/jpeg", "[Content_Types].xml")
        root.children.remove(link)
    }

    private fun whitespace(node: XmlElement) = node.children.filterIsInstance<XmlText>().all { it.text.isBlank() }

}
