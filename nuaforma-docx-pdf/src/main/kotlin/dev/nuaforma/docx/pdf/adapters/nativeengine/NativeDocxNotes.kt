package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText
import dev.nuaforma.pdf.*

/** Number source occurrences before pagination; source IDs and XML order are not display numbers. */
internal class NativeDocxNotes(
    roots: Map<PdfNoteKind, XmlElement>, private val numbering: Map<PdfNoteKind, Numbering>,
    private val fail: (String) -> Nothing,
) {
    data class Numbering(val start: Int, val format: PdfNumberFormat, val specialIds: Set<String> = emptySet())
    private data class Definition(val node: XmlElement, val kind: PdfNoteKind, val id: String)
    private data class Used(val definition: Definition, val value: PdfListValue)
    private val definitions = linkedMapOf<String, Definition>()
    private val used = linkedMapOf<String, Used>()
    private val counts = mutableMapOf<PdfNoteKind, Int>()
    private val separators = mutableMapOf<PdfNoteKind, PdfNoteSeparator>()

    init {
        for ((kind, root) in roots) {
            val name = kindName(kind)
            if (root.name.namespaceURI != W || root.name.localPart != name + "s") fail("Invalid notes root")
            val ids = mutableSetOf<String>()
            val specialIds = mutableSetOf<String>()
            val policies = mutableSetOf<String>()
            for (node in root.elements()) {
                if (node.name.namespaceURI != W || node.name.localPart != name || node.attributes.keys.any { it.namespaceURI != W || it.localPart !in setOf("id", "type") }) fail("Invalid note definition")
                val raw = sourceId(node.attribute(W, "id"), fail)
                if (!ids.add(raw)) fail("Duplicate note definition ID")
                when (val type = node.attribute(W, "type") ?: "normal") {
                    "normal" -> {
                        if (raw.toInt() < 0) fail("Normal notes require nonnegative IDs")
                        val id = id(kind, raw)
                        definitions[id] = Definition(node, kind, id)
                    }
                    "separator", "continuationSeparator" -> {
                        specialIds += raw
                        if (!policies.add(type)) fail("Duplicate note separator")
                        validateSeparator(node, type, fail)
                        separators[kind] = PdfNoteSeparator()
                    }
                    "continuationNotice" -> {
                        specialIds += raw
                        if (node.descendants().any { it.name.namespaceURI != W || it.name.localPart !in setOf(name, "p", "pPr", "r", "rPr", "t") ||
                                it.children.filterIsInstance<XmlText>().any { text -> text.text.isNotBlank() } }) fail("Visible note continuation notices are not qualified")
                    }
                    else -> fail("Unsupported note type")
                }
            }
            if (!specialIds.containsAll(numbering.getValue(kind).specialIds)) fail("Note separator references require special definitions")
            if (definitions.size > 1_000) fail("Source note budget exceeded")
        }
    }

    fun call(kind: PdfNoteKind, rawId: String?, style: PdfTextStyle, navigation: PdfTextNavigation?): PdfNoteCall {
        val id = id(kind, sourceId(rawId, fail))
        val definition = definitions[id] ?: fail("Missing note definition")
        if (id in used || used.size >= 500) fail("Duplicate or excessive owning note references")
        val policy = numbering.getValue(kind)
        val ordinal = policy.start + (counts[kind] ?: 0)
        if (ordinal !in 1..9_999) fail("Note numbering exceeds the qualified range")
        val instance = kindName(kind)
        val value = PdfNumbering.resolve(listOf(PdfListDefinition(instance, listOf(PdfListLevel(policy.format, "%1", ordinal)))), listOf(PdfListStep(instance, 0))).single()
        counts[kind] = (counts[kind] ?: 0) + 1
        used[id] = Used(definition, value)
        return PdfNoteCall("$id-call", id, style, navigation)
    }

    fun readDefinitions(read: (XmlElement, PdfNoteKind) -> List<PdfParagraph>): List<PdfNote> = used.values.map {
        PdfNote(it.definition.id, it.definition.kind, it.value, read(it.definition.node, it.definition.kind), separators[it.definition.kind] ?: PdfNoteSeparator())
    }

    companion object {
        private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        fun kindName(kind: PdfNoteKind) = if (kind == PdfNoteKind.FOOTNOTE) "footnote" else "endnote"
        private fun id(kind: PdfNoteKind, raw: String) = "source-${kindName(kind)}$raw"
        private fun sourceId(raw: String?, fail: (String) -> Nothing): String {
            if (raw == null || !raw.matches(Regex("-?[0-9]{1,9}"))) fail("Invalid note ID")
            return raw.toInt().toString()
        }
        fun defaults(): Map<PdfNoteKind, Numbering> = mapOf(
            PdfNoteKind.FOOTNOTE to Numbering(1, PdfNumberFormat.DECIMAL), PdfNoteKind.ENDNOTE to Numbering(1, PdfNumberFormat.LOWER_ROMAN))

        fun properties(node: XmlElement?, kind: PdfNoteKind, previous: Numbering, fail: (String) -> Nothing): Numbering {
            if (node == null) return previous
            if (node.attributes.isNotEmpty()) fail("Unsupported note numbering properties")
            var result = previous
            val seen = mutableSetOf<String>()
            val selectedSpecials = mutableSetOf<String>()
            for (property in node.elements()) {
                val name = property.name.localPart
                if (property.name.namespaceURI != W || !seen.add(name) && name != kindName(kind) || property.elements().isNotEmpty()) fail("Invalid note numbering properties")
                if (name == kindName(kind)) {
                    if (property.attributes.size != 1 || property.attribute(W, "id") == null) fail("Invalid special note reference")
                    val id = sourceId(property.attribute(W, "id"), fail)
                    if (!selectedSpecials.add(id)) fail("Duplicate special note reference")
                    result = result.copy(specialIds = selectedSpecials.toSet()); continue
                }
                if (property.attributes.size != 1 || property.attribute(W, "val") == null) fail("Invalid note numbering property")
                val value = property.attribute(W, "val")
                when (name) {
                    "pos" -> if (value != if (kind == PdfNoteKind.FOOTNOTE) "pageBottom" else "docEnd") fail("Unsupported note placement")
                    "numStart" -> result = result.copy(start = value?.toIntOrNull()?.takeIf { it in 1..9_999 } ?: fail("Invalid note numbering start"))
                    "numRestart" -> if (value != "continuous") fail("Note numbering restarts are not yet qualified")
                    "numFmt" -> result = result.copy(format = when (value) {
                        "decimal" -> PdfNumberFormat.DECIMAL; "lowerRoman" -> PdfNumberFormat.LOWER_ROMAN; "upperRoman" -> PdfNumberFormat.UPPER_ROMAN
                        "lowerLetter" -> PdfNumberFormat.LOWER_LETTER; "upperLetter" -> PdfNumberFormat.UPPER_LETTER
                        else -> fail("Unsupported note number format")
                    })
                    else -> fail("Unsupported note property")
                }
            }
            return result
        }

        private fun validateSeparator(node: XmlElement, type: String, fail: (String) -> Nothing) {
            val allowed = setOf(node.name.localPart, "p", "pPr", "spacing", "r", type)
            if (node.descendants().any { it.name.namespaceURI != W || it.name.localPart !in allowed || it.children.filterIsInstance<XmlText>().any { text -> text.text.isNotBlank() } })
                fail("Unsupported note separator contents")
            if (node.descendants().count { it.name.localPart == type } != 1) fail("Missing or duplicate note separator control")
            if (node.elements().size != 1 || node.elements().single().name.localPart != "p") fail("Unsupported note separator paragraphs")
            if (node.descendants().any { it !== node && it.name.localPart != "spacing" && it.attributes.isNotEmpty() }) fail("Unsupported note separator attributes")
            node.descendants().filter { it.name.localPart == "spacing" }.forEach { spacing ->
                val expected = mapOf("before" to "0", "after" to "0", "line" to "240", "lineRule" to "exact")
                if (spacing.attributes.any { (key, value) -> key.namespaceURI != W || expected[key.localPart] != value }) fail("Unqualified note separator spacing")
            }
        }
    }
}
