package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText
import java.util.Locale

/** Resolve bounded literal fields from their definitions, never from a display cache. */
internal class NativeWordValues(
    private val context: GenerationContext,
    properties: Map<String, WordPackageMetadata.Property>,
    private val core: XmlElement?,
    private val focus: (XmlElement) -> Unit,
    private val reject: (String) -> Nothing,
) {
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val coreNamespace = "http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
    private val dc = "http://purl.org/dc/elements/1.1/"
    private val propertiesByName = properties.entries.filter { it.key.length <= 128 }
        .groupBy({ it.key.lowercase(Locale.ROOT) }, { it.value })
    private data class Assignment(val value: String, val source: XmlElement)
    private val assignments = mutableMapOf<String, Assignment>()
    private var emitted = 0L

    fun index(roots: Collection<XmlElement>) {
        val bookmarkNames = mutableSetOf<String>()
        val stories = roots.filter { it.name.namespaceURI == word && it.name.localPart in setOf("document", "body", "hdr", "ftr", "footnotes", "endnotes") }
        for (root in stories) for (node in root.descendants()) {
            context.checkActive()
            if (node.name.namespaceURI != word) continue
            if (node.name.localPart == "bookmarkStart") node.attribute(word, "name")?.let { bookmarkNames += it }
            if (node.name.localPart != "fldSimple") continue
            focus(node)
            val instruction = NativeDocxFields.instruction(node.attribute(word, "instr") ?: reject("Missing field instruction"), reject)
            if (instruction.kind != "SET") continue
            val key = requireNotNull(instruction.argument)
            if (assignments.putIfAbsent(key, Assignment(requireNotNull(instruction.literal), node)) != null || assignments.size > 1_000) reject("Duplicate or excessive SET assignments")
        }
        assignments.keys.firstOrNull { it in bookmarkNames }?.let { key ->
            focus(assignments.getValue(key).source)
            reject("SET assignment conflicts with a visible bookmark")
        }
    }

    fun resolve(instruction: NativeDocxFields.Instruction): String? = when {
        instruction.kind == "DOCPROPERTY" -> bounded(property(requireNotNull(instruction.argument)))
        instruction.kind == "SET" -> ""
        instruction.argument in assignments && instruction.kind == "REF" -> {
            if (instruction.linked) reject("Linked SET references are outside this profile")
            bounded(assignments.getValue(requireNotNull(instruction.argument)).value)
        }
        instruction.argument in assignments && instruction.kind == "PAGEREF" -> reject("SET assignments have no physical page destination")
        else -> null
    }

    private fun property(name: String): String {
        val normalized = name.lowercase(Locale.ROOT)
        val custom = propertiesByName[normalized].orEmpty()
        if (custom.size > 1) reject("Ambiguous document property name")
        val builtin = when (normalized) {
            "title" -> dc to "title"
            "subject" -> dc to "subject"
            "author" -> dc to "creator"
            "comments" -> dc to "description"
            "keywords", "category", "contentstatus", "version", "lastsavedby" -> coreNamespace to when (normalized) {
                "contentstatus" -> "contentStatus"; "lastsavedby" -> "lastModifiedBy"; else -> normalized
            }
            else -> null
        }
        val nodes = if (builtin == null || core == null) emptyList() else {
            if (core.name.namespaceURI != coreNamespace || core.name.localPart != "coreProperties") reject("Invalid core document properties")
            core.elements().filter { it.name.namespaceURI == builtin.first && it.name.localPart == builtin.second }
        }
        if (nodes.size > 1 || nodes.isNotEmpty() && custom.isNotEmpty()) reject("Ambiguous document property name")
        nodes.singleOrNull()?.let { node ->
            if (node.elements().isNotEmpty() || node.attributes.keys.any { it.namespaceURI != "http://www.w3.org/XML/1998/namespace" || it.localPart != "lang" }) reject("Unsupported core document property value")
            return node.children.filterIsInstance<XmlText>().joinToString("") { it.text }
        }
        val value = custom.singleOrNull() ?: reject("Document property is missing")
        if (value.type !in setOf("lpwstr", "lpstr", "bstr")) reject("Unsupported document property value type")
        return value.text
    }

    private fun bounded(value: String): String {
        context.checkActive()
        if (value.length > 16_384 || value.any { it.isISOControl() }) reject("Unsupported or excessive static field value")
        emitted += value.length
        if (emitted > minOf(1_000_000L, context.limits.maxExpandedBytes / 2)) reject("Static field value budget exceeded")
        return value
    }

}
