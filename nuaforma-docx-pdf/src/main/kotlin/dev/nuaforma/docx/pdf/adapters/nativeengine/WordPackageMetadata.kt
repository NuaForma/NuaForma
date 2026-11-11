package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText

/** Validate inert package metadata after the DOCX adapter has detached passive template references. */
internal class WordPackageMetadata(private val context: GenerationContext) {
    private val office = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private val relations = "http://schemas.openxmlformats.org/package/2006/relationships"
    private val custom = "http://schemas.openxmlformats.org/officeDocument/2006/custom-properties"
    private val values = "http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes"
    private val propertiesPart = "docProps/custom.xml"
    private val settingsPart = "word/_rels/settings.xml.rels"

    data class Property(val type: String, val text: String)

    fun removeFromPrintModel(parts: MutableMap<String, XmlElement>): Map<String, Property> {
        parts[settingsPart]?.let { root ->
            valid(root.name.namespaceURI == relations && root.name.localPart == "Relationships" && root.attributes.isEmpty() &&
                root.elements().isEmpty() && whitespace(root), settingsPart)
            valid("word/settings.xml" in parts, settingsPart)
            parts.remove(settingsPart)
        }
        val rootRelations = parts["_rels/.rels"] ?: return emptyMap()
        val links = rootRelations.elements().filter { it.attribute("", "Type") == "$office/custom-properties" }
        val properties = parts[propertiesPart]
        if (links.isEmpty() && properties == null) return emptyMap()
        valid(rootRelations.name.namespaceURI == relations && rootRelations.name.localPart == "Relationships", "_rels/.rels")
        val ids = rootRelations.elements().map { it.attribute("", "Id") }
        valid(ids.all { !it.isNullOrBlank() } && ids.distinct().size == ids.size && links.size == 1, "_rels/.rels")
        val link = links.single()
        valid(link.name.namespaceURI == relations && link.name.localPart == "Relationship" && link.elements().isEmpty() && whitespace(link) &&
            link.attributes.keys.all { it.namespaceURI.isEmpty() && it.localPart in setOf("Id", "Type", "Target", "TargetMode") } &&
            link.attribute("", "Target") == propertiesPart && link.attribute("", "TargetMode") in setOf(null, "Internal"), "_rels/.rels")
        validateProperties(properties ?: fail(propertiesPart))
        val contentTypes = parts["[Content_Types].xml"] ?: fail("[Content_Types].xml")
        val overrides = contentTypes.elements().filter { it.attribute("", "PartName") == "/$propertiesPart" }
        valid(overrides.size == 1 && overrides.single().name.namespaceURI == "http://schemas.openxmlformats.org/package/2006/content-types" &&
            overrides.single().name.localPart == "Override" && overrides.single().attribute("", "ContentType") ==
            "application/vnd.openxmlformats-officedocument.custom-properties+xml", "[Content_Types].xml")
        rootRelations.children.removeAll(links.toSet())
        parts.remove(propertiesPart)
        return requireNotNull(properties).elements().associate { property ->
            val value = property.elements().single()
            requireNotNull(property.attribute("", "name")) to Property(value.name.localPart,
                value.children.filterIsInstance<XmlText>().joinToString("") { it.text })
        }
    }

    private fun validateProperties(root: XmlElement) {
        valid(root.name.namespaceURI == custom && root.name.localPart == "Properties" && root.attributes.isEmpty() && whitespace(root), propertiesPart)
        val ids = mutableSetOf<Int>()
        val names = mutableSetOf<String>()
        for (property in root.elements()) {
            context.checkActive()
            valid(property.name.namespaceURI == custom && property.name.localPart == "property" && whitespace(property) &&
                property.attributes.keys.all { it.namespaceURI.isEmpty() && it.localPart in setOf("fmtid", "pid", "name") }, propertiesPart)
            valid(property.attribute("", "fmtid")?.uppercase(java.util.Locale.ROOT) == "{D5CDD505-2E9C-101B-9397-08002B2CF9AE}", propertiesPart)
            val id = property.attribute("", "pid")?.toIntOrNull()
            val name = property.attribute("", "name")
            valid(id != null && id >= 2 && ids.add(id) && !name.isNullOrBlank() && names.add(name), propertiesPart)
            val value = property.elements().singleOrNull() ?: fail(propertiesPart)
            valid(value.name.namespaceURI == values && value.attributes.isEmpty() && value.elements().isEmpty(), propertiesPart)
            val text = value.children.filterIsInstance<XmlText>().joinToString("") { it.text }
            val scalar = when (value.name.localPart) {
                "lpwstr", "lpstr", "bstr" -> true
                "bool" -> text in setOf("true", "false", "0", "1")
                "i1", "i2", "i4", "i8", "int", "ui1", "ui2", "ui4", "ui8", "uint" -> text.matches(Regex("[+-]?[0-9]+")) &&
                    (!value.name.localPart.startsWith("u") || !text.startsWith("-"))
                "r4", "r8", "decimal" -> text.toDoubleOrNull()?.isFinite() == true
                "filetime", "date" -> try { java.time.OffsetDateTime.parse(text); true } catch (_: java.time.format.DateTimeParseException) { false }
                else -> false
            }
            valid(scalar, propertiesPart)
        }
    }

    private fun whitespace(node: XmlElement) = node.children.filterIsInstance<XmlText>().all { it.text.isBlank() }

    private fun valid(condition: Boolean, part: String) { if (!condition) fail(part) }

    private fun fail(part: String): Nothing = throw NativeDocxReadException("Unsupported or invalid passive package metadata", part)

}
