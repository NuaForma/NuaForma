package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText
import dev.nuaforma.pdf.*
import java.util.IdentityHashMap

/** Source order owns counters; unused validated definitions do not require render qualification. */
internal class NativeDocxNumbering(root: XmlElement?, body: XmlElement, private val focus: (XmlElement) -> Unit = {}, styles: (XmlElement) -> List<XmlElement>) {
    data class Entry(val level: XmlElement, val value: PdfListValue, val alignment: PdfAlignment, val suffix: PdfListSuffix)
    private data class Instance(val levels: List<XmlElement>, val abstractId: String, val starts: Map<Int, Int>)
    private val entries = IdentityHashMap<XmlElement, Entry>()

    fun entry(p: XmlElement) = entries[p]

    fun alias(source: XmlElement, copy: XmlElement) { entries[source]?.let { entries[copy] = it } }

    init {
        val instances = if (root == null) emptyMap() else parse(root)
        val selected = mutableListOf<Pair<XmlElement, PdfListStep>>()
        val startedOverrides = mutableSetOf<Pair<String, Int>>()
        val frames = body.descendants().filter { it.name.namespaceURI == W && it.name.localPart == "txbxContent" }.flatMap { it.descendants() }.toSet()
        for (p in body.descendants().filter { it.name.namespaceURI == W && it.name.localPart == "p" }) {
            focus(p)
            val chain = styles(p)
            var id: String? = null
            var inheritedLevel: Int? = null
            for (style in chain) style.one("pPr")?.one("numPr")?.let {
                val properties = numProperties(it)
                id = properties.first ?: id
                inheritedLevel = properties.second ?: inheritedLevel
            }
            val direct = p.one("pPr")?.one("numPr")?.let(::numProperties)
            id = direct?.first ?: id
            focus(p)
            if (id == "0") continue
            if (id == null) { require(direct?.second == null) { "List level without an instance" }; continue }
            require(p !in frames) { "Numbering in text frames is outside the qualified source profile" }
            val instance = requireNotNull(instances[id]) { "Unknown numbering instance" }
            val level = direct?.second ?: inheritedLevel ?: 0
            require(level in instance.levels.indices) { "Unknown numbering level" }
            require(selected.size < 2_000) { "Source list step budget exceeded" }
            val restart = level in instance.starts && startedOverrides.add(id to level)
            selected += p to PdfListStep(id, level, restart)
        }
        val definitions = selected.map { it.second.instanceId }.distinct().map { id ->
            val instance = instances.getValue(id)
            PdfListDefinition(id, instance.levels.mapIndexed { index, level -> rule(level, instance.starts[index]) }, "sequence_${instance.abstractId}")
        }
        val values = resolve(definitions, selected, root ?: body)
        selected.zip(values).forEach { (pair, value) ->
            val level = instances.getValue(pair.second.instanceId).levels[pair.second.level]
            focus(level)
            val alignment = when (level.one("lvlJc")?.value("val") ?: "left") {
                "left" -> PdfAlignment.LEFT; "center" -> PdfAlignment.CENTER; "right" -> PdfAlignment.RIGHT
                else -> throw IllegalArgumentException("Unsupported numbering alignment")
            }
            val suffix = when (level.one("suff")?.value("val") ?: "tab") {
                "tab" -> PdfListSuffix.TAB; "space" -> PdfListSuffix.SPACE; "nothing" -> PdfListSuffix.NOTHING
                else -> throw IllegalArgumentException("Unsupported numbering suffix")
            }
            entries[pair.first] = Entry(level, value, alignment, suffix)
        }
    }

    private fun resolve(definitions: List<PdfListDefinition>, selected: List<Pair<XmlElement, PdfListStep>>, root: XmlElement): List<PdfListValue> {
        val steps = selected.map { it.second }
        try { return PdfNumbering.resolve(definitions, steps) }
        catch (error: IllegalArgumentException) {
            focus(root)
            PdfNumbering.resolve(definitions, emptyList())
            if (steps.isEmpty()) throw error
            var low = 1
            var high = steps.size
            while (low < high) {
                val mid = (low + high) / 2
                focus(selected[mid - 1].first)
                try { PdfNumbering.resolve(definitions, steps.take(mid)); low = mid + 1 }
                catch (_: IllegalArgumentException) { high = mid }
            }
            focus(selected[low - 1].first)
            throw error
        }
    }

    private fun parse(root: XmlElement): Map<String, Instance> {
        focus(root)
        require(root.name.namespaceURI == W && root.name.localPart == "numbering") { "Invalid numbering root" }
        children(root, setOf("abstractNum", "num")); attrs(root, emptySet())
        val abstracts = linkedMapOf<String, List<XmlElement>>()
        val nums = root.elements().filter { it.name.localPart == "num" }
        require(nums.size <= 128) { "Numbering instance budget exceeded" }
        for (abstract in root.elements().filter { it.name.localPart == "abstractNum" }) {
            attrs(abstract, setOf("abstractNumId")); children(abstract, setOf("nsid", "tmpl", "multiLevelType", "lvl"))
            require(abstracts.size < 128) { "Abstract numbering budget exceeded" }
            for (meta in abstract.elements().filter { it.name.localPart != "lvl" }) { attrs(meta, setOf("val")); require(meta.elements().isEmpty()) }
            abstract.one("multiLevelType")?.let { require(it.value("val") in setOf("singleLevel", "multilevel", "hybridMultilevel")) }
            val levels = abstract.elements().filter { it.name.localPart == "lvl" }.sortedBy { integer(it.value("ilvl")) }
            require(levels.size in 1..9 && levels.withIndex().all { (index, level) -> integer(level.value("ilvl")) == index }) { "Noncontiguous numbering levels" }
            levels.forEach(::validateLevel)
            focus(abstract)
            require(abstracts.put(id(abstract.value("abstractNumId")), levels) == null) { "Duplicate abstract numbering" }
        }
        val result = linkedMapOf<String, Instance>()
        for (num in nums) {
            attrs(num, setOf("numId")); children(num, setOf("abstractNumId", "lvlOverride"))
            val identifier = id(num.value("numId")); require(identifier != "0") { "Reserved numbering instance" }
            val reference = requireNotNull(num.one("abstractNumId")); attrs(reference, setOf("val"))
            val abstractId = id(reference.value("val"))
            val levels = requireNotNull(abstracts[abstractId]) { "Unknown abstract numbering" }.toMutableList()
            val starts = mutableMapOf<Int, Int>()
            val seen = mutableSetOf<Int>()
            for (override in num.elements().filter { it.name.localPart == "lvlOverride" }) {
                attrs(override, setOf("ilvl")); children(override, setOf("startOverride", "lvl"))
                val index = integer(override.value("ilvl")); require(index in levels.indices && seen.add(index)) { "Invalid level override" }
                override.one("lvl")?.let { level ->
                    validateLevel(level)
                    require(integer(level.value("ilvl")) == index) { "Mismatched full level override" }
                    levels[index] = level
                }
                override.one("startOverride")?.let { attrs(it, setOf("val")); children(it, emptySet()); starts[index] = integer(it.value("val")) }
            }
            focus(num)
            require(result.put(identifier, Instance(levels.toList(), abstractId, starts.toMap())) == null) { "Duplicate numbering instance" }
        }
        return result
    }

    private fun validateLevel(level: XmlElement) {
        attrs(level, setOf("ilvl")); children(level, setOf("start", "numFmt", "lvlText", "lvlRestart", "isLgl", "pStyle", "suff", "lvlJc", "pPr", "rPr", "legacy"))
        for (leaf in level.elements().filter { it.name.localPart !in setOf("pPr", "rPr", "legacy") }) { attrs(leaf, setOf("val")); children(leaf, emptySet()) }
        focus(level)
        require(level.one("numFmt")?.value("val") in setOf(null, "none", "decimal", "decimalZero", "lowerLetter", "upperLetter", "lowerRoman", "upperRoman", "bullet")) { "Unsupported numbering format" }
        level.one("lvlText")?.let { text ->
            require(requireNotNull(text.value("val")) { "Missing list pattern value" }.length <= 128) { "Invalid list pattern" }
        }
        level.one("start")?.let { integer(it.value("val")) }
        level.one("lvlRestart")?.let { require(integer(it.value("val")) <= integer(level.value("ilvl"))) { "Restart ancestor outside the qualified subset" } }
        level.one("isLgl")?.let { flag(it) }
        level.one("lvlJc")?.let { require(it.value("val") in setOf("left", "center", "right")) { "Unsupported numbering alignment" } }
        level.one("suff")?.let { require(it.value("val") in setOf("tab", "space", "nothing")) { "Unsupported numbering suffix" } }
        level.one("legacy")?.let { legacy ->
            attrs(legacy, setOf("legacy", "legacySpace", "legacyIndent")); children(legacy, emptySet())
            flag(legacy, "legacy")
            legacy.value("legacySpace")?.let(::integer)
            legacy.value("legacyIndent")?.let { require(it.toIntOrNull() in -1_000_000..1_000_000) { "Invalid legacy numbering indentation" } }
        }
    }

    private fun rule(level: XmlElement, start: Int?): PdfListLevel {
        focus(level)
        require(level.one("numFmt")?.value("val") != "none") { "Visible unnumbered list levels are outside the qualified profile" }
        require(level.one("legacy")?.let { flag(it, "legacy") } != true) { "Visible legacy list geometry is outside the qualified profile" }
        val format = when (level.one("numFmt")?.value("val") ?: "decimal") {
            "decimal" -> PdfNumberFormat.DECIMAL; "decimalZero" -> PdfNumberFormat.DECIMAL_ZERO
            "lowerLetter" -> PdfNumberFormat.LOWER_LETTER; "upperLetter" -> PdfNumberFormat.UPPER_LETTER
            "lowerRoman" -> PdfNumberFormat.LOWER_ROMAN; "upperRoman" -> PdfNumberFormat.UPPER_ROMAN; "bullet" -> PdfNumberFormat.BULLET
            else -> throw IllegalArgumentException("Unsupported numbering format")
        }
        val restart = level.one("lvlRestart")?.let { integer(it.value("val")) }
        val legal = level.one("isLgl")?.let { flag(it) } ?: false
        return PdfListLevel(format, requireNotNull(level.one("lvlText")?.value("val")) { "Missing visible list pattern" },
            start ?: level.one("start")?.let { integer(it.value("val")) } ?: 0,
            restart?.minus(1), legal)
    }

    private fun numProperties(node: XmlElement): Pair<String?, Int?> {
        attrs(node, emptySet()); children(node, setOf("numId", "ilvl")); require(node.elements().isNotEmpty()) { "Empty numbering reference" }
        node.elements().forEach { attrs(it, setOf("val")); require(it.elements().isEmpty()) }
        return node.one("numId")?.let { id(it.value("val")) } to node.one("ilvl")?.let { integer(it.value("val")) }
    }

    private fun flag(node: XmlElement, name: String = "val"): Boolean {
        focus(node)
        return when (node.value(name)) {
            null -> name == "val"
            "1", "true", "on" -> true; "0", "false", "off" -> false
            else -> throw IllegalArgumentException("Invalid numbering toggle")
        }
    }

    private fun integer(value: String?) = requireNotNull(value?.toIntOrNull()?.takeIf { it in 0..1_000_000 }) { "Invalid numbering integer" }

    private fun id(value: String?) = integer(value).toString()

    private fun attrs(node: XmlElement, allowed: Set<String>) {
        focus(node)
        require(node.attributes.keys.all { it.namespaceURI == W && it.localPart in allowed }) { "Unsupported numbering attribute" }
    }

    private fun children(node: XmlElement, allowed: Set<String>) {
        focus(node)
        val unsupported = node.elements().firstOrNull { it.name.namespaceURI != W || it.name.localPart !in allowed }
        if (unsupported != null) {
            focus(unsupported)
            throw IllegalArgumentException("Unsupported numbering feature")
        }
        require(node.children.filterIsInstance<XmlText>().all { it.text.isBlank() }) { "Unexpected numbering text" }
        val duplicate = node.elements().groupBy { it.name.localPart }.values.firstOrNull {
            it.first().name.localPart !in setOf("abstractNum", "num", "lvl", "lvlOverride") && it.size > 1
        }
        if (duplicate != null) {
            focus(duplicate[1])
            throw IllegalArgumentException("Duplicate numbering property")
        }
    }

    private fun XmlElement.one(name: String) = elements().singleOrNull { it.name.namespaceURI == W && it.name.localPart == name }

    private fun XmlElement.value(name: String) = attribute(W, name)

    private companion object { const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main" }

}
