package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText
import dev.nuaforma.pdf.*

/** Number source occurrences before pagination; source IDs and XML order are not display numbers. */
internal class NativeDocxNotes(
    roots: Map<PdfNoteKind, XmlElement>, private val numbering: Map<PdfNoteKind, Numbering>,
    private val fail: (String) -> Nothing,
    focus: (XmlElement) -> Unit = {},
) {
    data class Numbering(val start: Int, val format: PdfNumberFormat, val specialIds: Set<String> = emptySet(), val chicago: Boolean = false)
    private data class Definition(val node: XmlElement, val kind: PdfNoteKind, val id: String)
    private data class Used(val definition: Definition, val value: PdfListValue)
    private val definitions = linkedMapOf<String, Definition>()
    private val used = linkedMapOf<String, Used>()
    private val counts = mutableMapOf<PdfNoteKind, Int>()
    private val separators = mutableMapOf<PdfNoteKind, PdfNoteSeparator>()

    init {
        for ((kind, root) in roots) {
            focus(root)
            val name = kindName(kind)
            if (root.name.namespaceURI != W || root.name.localPart != name + "s") fail("Invalid notes root")
            val ids = mutableSetOf<String>()
            val specialIds = mutableSetOf<String>()
            val policies = mutableSetOf<String>()
            for (node in root.elements()) {
                focus(node)
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
        val value = if (policy.chicago) {
            if (ordinal > 128) fail("Chicago note labels exceed the qualified length")
            PdfListValue(instance, 0, ordinal, listOf("*", "\u2020", "\u2021", "\u00a7")[(ordinal - 1) % 4].repeat((ordinal - 1) / 4 + 1))
        } else PdfNumbering.resolve(listOf(PdfListDefinition(instance, listOf(PdfListLevel(policy.format, "%1", ordinal)))), listOf(PdfListStep(instance, 0))).single()
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
                    // Active notes are bounded to one section by the reader. Physical pages
                    // do not restart either policy; multi-section notes remain explicit failures.
                    "numRestart" -> if (value !in setOf("continuous", "eachSect")) fail("Note numbering restarts are not yet qualified")
                    "numFmt" -> result = result.copy(chicago = value == "chicago", format = when (value) {
                        "chicago" -> PdfNumberFormat.DECIMAL
                        "decimal" -> PdfNumberFormat.DECIMAL; "lowerRoman" -> PdfNumberFormat.LOWER_ROMAN; "upperRoman" -> PdfNumberFormat.UPPER_ROMAN
                        "lowerLetter" -> PdfNumberFormat.LOWER_LETTER; "upperLetter" -> PdfNumberFormat.UPPER_LETTER
                        else -> fail("Unsupported note number format")
                    })
                    else -> fail("Unsupported note property")
                }
            }
            return result
        }

        /** Validate newly qualified nonprinting run properties before typed binding. */
        fun validateSeparatorSource(root: XmlElement, focus: (XmlElement) -> Unit, fail: (String) -> Nothing) {
            if (root.name.namespaceURI != W || root.name.localPart !in setOf("footnotes", "endnotes")) return
            for (note in root.elements().filter { it.attribute(W, "type") in setOf("separator", "continuationSeparator") }) {
                for (run in note.descendants().filter { it.name.localPart == "r" }) {
                    if (run.elements().count { it.name.localPart == "rPr" } > 1) { focus(run); fail("Duplicate note separator run properties") }
                }
                for (node in note.descendants().filter { it.name.localPart == "rPr" }) {
                    focus(node)
                    val onReference = note.descendants().any { run -> run.name.localPart == "r" &&
                        run.elements().any { it === node } && run.elements().any { it.name.localPart == note.name.localPart + "Ref" } }
                    separatorRunProperties(node, fail, allowStyle = onReference)
                }
                for (node in note.descendants().filter { it.name.localPart in setOf("footnoteRef", "endnoteRef") }) {
                    focus(node)
                    if (node.name.namespaceURI != W || node.attributes.isNotEmpty() || node.children.isNotEmpty()) fail("Invalid note separator reference")
                }
            }
        }

        private fun separatorRunProperties(node: XmlElement, fail: (String) -> Nothing, allowStyle: Boolean = true) {
            if (node.name.namespaceURI != W || node.attributes.isNotEmpty() ||
                node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() }) fail("Invalid note separator run properties")
            val properties = node.elements()
            if (properties.map { it.name.localPart }.distinct().size != properties.size) fail("Duplicate note separator property")
            for (property in properties) {
                if (property.name.namespaceURI != W || property.elements().isNotEmpty() ||
                    property.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() } ||
                    property.attributes.size != 1 || property.attribute(W, "val") == null) fail("Invalid note separator property")
                val value = requireNotNull(property.attribute(W, "val"))
                when (property.name.localPart) {
                    "sz" -> if (value !in setOf("24", "36")) fail("Unqualified note separator control size")
                    "rStyle" -> if (!allowStyle || !value.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,127}"))) fail("Invalid note separator control style")
                    else -> fail("Unsupported note separator control property")
                }
            }
        }

        private fun validateSeparator(node: XmlElement, type: String, fail: (String) -> Nothing) {
            val reference = node.name.localPart + "Ref"
            val allowed = setOf(node.name.localPart, "p", "pPr", "spacing", "r", "rPr", "rStyle", "sz", type, reference)
            if (node.descendants().any { it.name.namespaceURI != W || it.name.localPart !in allowed || it.children.filterIsInstance<XmlText>().any { text -> text.text.isNotBlank() } })
                fail("Unsupported note separator contents")
            if (node.descendants().count { it.name.localPart == type } != 1) fail("Missing or duplicate note separator control")
            if (node.elements().size != 1 || node.elements().single().name.localPart != "p") fail("Unsupported note separator paragraphs")
            val paragraph = node.elements().single()
            val properties = paragraph.elements().filter { it.name.localPart == "pPr" }
            val runs = paragraph.elements().filter { it.name.localPart == "r" }
            if (properties.size > 1 || properties.any { it.elements().size > 1 || it.elements().any { child -> child.name.localPart != "spacing" || child.elements().isNotEmpty() } } ||
                runs.size !in 1..2 || paragraph.elements().any { it.name.localPart !in setOf("pPr", "r") }) fail("Unsupported note separator structure")
            for ((index, run) in runs.withIndex()) {
                val children = run.elements()
                val control = if (index == runs.lastIndex) type else reference
                if (children.size !in 1..2 || children.last().name.localPart != control || children.last().elements().isNotEmpty() ||
                    children.dropLast(1).any { it.name.localPart != "rPr" }) fail("Unsupported note separator run")
                children.firstOrNull()?.takeIf { it.name.localPart == "rPr" }?.let { separatorRunProperties(it, fail, allowStyle = control == reference) }
            }
            if (node.descendants().any { it !== node && it.name.localPart !in setOf("spacing", "rStyle", "sz") && it.attributes.isNotEmpty() }) fail("Unsupported note separator attributes")
            node.descendants().filter { it.name.localPart == "spacing" }.forEach { spacing ->
                val expected = mapOf("before" to "0", "after" to "0", "line" to "240", "lineRule" to "exact")
                if (spacing.attributes.any { (key, value) -> key.namespaceURI != W ||
                        if (key.localPart == "lineRule") value !in setOf("exact", "auto") else expected[key.localPart] != value } ||
                    spacing.attribute(W, "lineRule") == "auto" && spacing.attribute(W, "line") != "240") fail("Unqualified note separator spacing")
            }
        }
    }
}
