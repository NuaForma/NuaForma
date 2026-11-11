package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText

/** Classification metadata is not print content; existing page markings remain ordinary document objects. */
internal class WordSensitivityMetadata(private val context: GenerationContext) {
    private val part = "docMetadata/LabelInfo.xml"
    private val namespace = "http://schemas.microsoft.com/office/2020/mipLabelMetadata"
    private val relation = "http://schemas.microsoft.com/office/2020/02/relationships/classificationlabels"
    private val packageNamespace = "http://schemas.openxmlformats.org/package/2006/relationships"
    private val typesNamespace = "http://schemas.openxmlformats.org/package/2006/content-types"
    private val guid = Regex("\\{[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}")

    fun removeFromPrintModel(parts: MutableMap<String, XmlElement>) {
        val relations = parts["_rels/.rels"]
        val links = relations?.elements().orEmpty().filter { it.attribute("", "Type") == relation }
        val root = parts[part]
        if (links.isEmpty() && root == null) return
        valid(relations != null && relations.name.namespaceURI == packageNamespace && relations.name.localPart == "Relationships")
        val ids = relations!!.elements().map { it.attribute("", "Id") }
        valid(ids.all { !it.isNullOrBlank() } && ids.distinct().size == ids.size && links.size == 1)
        val link = links.single()
        valid(link.name.namespaceURI == packageNamespace && link.name.localPart == "Relationship" && inert(link) && link.elements().isEmpty() &&
            link.attributes.keys.all { it.namespaceURI.isEmpty() && it.localPart in setOf("Id", "Type", "Target", "TargetMode") } &&
            link.attribute("", "Target") == part && link.attribute("", "TargetMode") in setOf(null, "Internal"))
        valid(root != null && root.name.namespaceURI == namespace && root.name.localPart == "labelList" && root.attributes.isEmpty() && inert(root))
        val tenants = mutableSetOf<String>()
        valid(root!!.elements().size <= 64)
        for (label in root.elements()) {
            context.checkActive()
            valid(label.name.namespaceURI == namespace && label.name.localPart == "label" && inert(label) && label.elements().isEmpty() &&
                label.attributes.keys.all { it.namespaceURI.isEmpty() && it.localPart in setOf("id", "siteId", "enabled", "removed", "method", "contentBits") })
            val id = label.attribute("", "id")
            val tenant = label.attribute("", "siteId")
            valid(id != null && guid.matches(id) && tenant != null && guid.matches(tenant) && tenants.add(tenant.lowercase(java.util.Locale.ROOT)))
            val enabled = boolean(label, "enabled")
            val removed = boolean(label, "removed")
            valid(if (removed) !enabled && label.attribute("", "method") in setOf(null, "") else label.attribute("", "method") in setOf("Standard", "Privileged"))
            val bits = label.attribute("", "contentBits") ?: "0"
            // Encryption and unknown policy actions cannot be represented by this static PDF conversion.
            valid(bits.matches(Regex("[0-9]+")) && bits.toLongOrNull() in 0L..7L)
        }
        val types = parts["[Content_Types].xml"]
        val overrides = types?.elements().orEmpty().filter { it.attribute("", "PartName") == "/$part" }
        valid(types?.name?.namespaceURI == typesNamespace && types.name.localPart == "Types" && overrides.size == 1)
        val override = overrides.single()
        valid(override.name.namespaceURI == typesNamespace && override.name.localPart == "Override" && inert(override) && override.elements().isEmpty() &&
            override.attributes.keys.all { it.namespaceURI.isEmpty() && it.localPart in setOf("PartName", "ContentType") } &&
            override.attribute("", "ContentType") in setOf("application/vnd.ms-office.classificationlabels+xml", "application/xml"))
        relations.children.removeAll(links.toSet())
        parts.remove(part)
    }

    private fun boolean(node: XmlElement, name: String): Boolean {
        val value = node.attribute("", name)
        valid(value in setOf(null, "true", "false", "0", "1"))
        return value in setOf("true", "1")
    }

    private fun inert(node: XmlElement) = node.children.filterIsInstance<XmlText>().all { it.text.isBlank() }

    private fun valid(condition: Boolean) {
        if (!condition) throw NativeDocxReadException("Unsupported or invalid sensitivity-label metadata", part)
    }
}
