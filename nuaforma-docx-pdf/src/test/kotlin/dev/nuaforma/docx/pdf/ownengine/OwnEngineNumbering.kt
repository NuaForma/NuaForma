package dev.nuaforma.docx.pdf.ownengine

import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.pdf.*
import java.util.IdentityHashMap

/** Strict fixture adapter. Source-specific style/override precedence stops at this boundary. */
internal class OwnEngineNumbering(root: XmlElement?, body: XmlElement, styles: (XmlElement) -> List<XmlElement>) {
    data class Entry(val level: XmlElement, val value: PdfListValue, val alignment: PdfAlignment, val suffix: PdfListSuffix)
    private data class Instance(val levels: List<XmlElement>, val definition: PdfListDefinition, val overrides: Set<Int>)
    private val entries = IdentityHashMap<XmlElement, Entry>()
    fun entry(p: XmlElement) = entries[p]
    fun alias(source: XmlElement, copy: XmlElement) { entries[source]?.let { entries[copy] = it } }

    init {
        val instances = if (root == null) emptyMap() else parse(root)
        val selected = mutableListOf<Pair<XmlElement, PdfListStep>>()
        val startedOverrides = mutableSetOf<Pair<String, Int>>()
        val directBody = body.elements().filter { it.name.localPart == "p" }.toSet()
        for (p in body.descendants().filter { it.name.namespaceURI == W && it.name.localPart == "p" }) {
            val chain = styles(p)
            var id: String? = null
            for (style in chain) style.one("pPr")?.one("numPr")?.let { id = numProperties(it).first ?: id }
            val direct = p.one("pPr")?.one("numPr")?.let(::numProperties)
            id = direct?.first ?: id
            if (id == "0") continue
            if (id == null) { require(direct?.second == null) { "List level without an instance" }; continue }
            require(p in directBody) { "Numbering in tables or frames is outside the qualified source profile" }
            val instance = requireNotNull(instances[id]) { "Unknown numbering instance" }
            val associated = chain.asReversed().firstNotNullOfOrNull { style ->
                instance.levels.indexOfFirst { it.one("pStyle")?.value("val") == style.value("styleId") }.takeIf { it >= 0 }
            }
            val level = direct?.second ?: associated ?: 0
            for (style in chain) style.one("pPr")?.one("numPr")?.let { node ->
                val declared = numProperties(node).second
                require(declared == null || associated != null && declared == associated) { "Inconsistent style-linked numbering level is outside the qualified profile" }
            }
            require(direct?.second == null || associated == null || direct.second == associated) { "Overriding a linked style level is outside the qualified profile" }
            require(level in instance.levels.indices) { "Unknown numbering level" }
            val restart = level in instance.overrides && startedOverrides.add(id to level)
            selected += p to PdfListStep(id, level, restart)
        }
        val values = PdfNumbering.resolve(instances.values.map { it.definition }, selected.map { it.second })
        selected.zip(values).forEach { (pair, value) ->
            val level = instances.getValue(pair.second.instanceId).levels[pair.second.level]
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

    private fun parse(root: XmlElement): Map<String, Instance> {
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
                attrs(override, setOf("ilvl")); children(override, setOf("startOverride"))
                val index = integer(override.value("ilvl")); require(index in levels.indices && seen.add(index)) { "Invalid level override" }
                require(override.elements().isNotEmpty()) { "Empty level override" }
                override.one("startOverride")?.let { attrs(it, setOf("val")); starts[index] = integer(it.value("val")) }
            }
            val rules = levels.mapIndexed { index, level -> rule(level, index, starts[index]) }
            require(result.put(identifier, Instance(levels.toList(), PdfListDefinition(identifier, rules, "sequence_$abstractId"), starts.keys.toSet())) == null) { "Duplicate numbering instance" }
        }
        return result
    }

    private fun rule(level: XmlElement, index: Int, start: Int?): PdfListLevel {
        attrs(level, setOf("ilvl")); children(level, setOf("start", "numFmt", "lvlText", "lvlRestart", "isLgl", "pStyle", "suff", "lvlJc", "pPr", "rPr"))
        for (leaf in level.elements().filter { it.name.localPart !in setOf("pPr", "rPr") }) { attrs(leaf, setOf("val")); require(leaf.elements().isEmpty()) }
        val format = when (level.one("numFmt")?.value("val") ?: "decimal") {
            "decimal" -> PdfNumberFormat.DECIMAL; "decimalZero" -> PdfNumberFormat.DECIMAL_ZERO
            "lowerLetter" -> PdfNumberFormat.LOWER_LETTER; "upperLetter" -> PdfNumberFormat.UPPER_LETTER
            "lowerRoman" -> PdfNumberFormat.LOWER_ROMAN; "upperRoman" -> PdfNumberFormat.UPPER_ROMAN; "bullet" -> PdfNumberFormat.BULLET
            else -> throw IllegalArgumentException("Unsupported numbering format")
        }
        val restart = level.one("lvlRestart")?.let { integer(it.value("val")) }
        require(restart == null || restart <= index) { "Restart ancestor outside the qualified subset" }
        val legal = level.one("isLgl")?.let { when (it.value("val")) {
            null, "1", "true", "on" -> true; "0", "false", "off" -> false; else -> throw IllegalArgumentException("Invalid legal-numbering toggle")
        } } ?: false
        return PdfListLevel(format, requireNotNull(level.one("lvlText")?.value("val")),
            start ?: level.one("start")?.let { integer(it.value("val")) } ?: 0,
            restart?.minus(1), legal)
    }

    private fun numProperties(node: XmlElement): Pair<String?, Int?> {
        attrs(node, emptySet()); children(node, setOf("numId", "ilvl")); require(node.elements().isNotEmpty()) { "Empty numbering reference" }
        node.elements().forEach { attrs(it, setOf("val")); require(it.elements().isEmpty()) }
        return node.one("numId")?.let { id(it.value("val")) } to node.one("ilvl")?.let { integer(it.value("val")) }
    }
    private fun integer(value: String?) = requireNotNull(value?.toIntOrNull()?.takeIf { it in 0..1_000_000 }) { "Invalid numbering integer" }
    private fun id(value: String?) = integer(value).toString()
    private fun attrs(node: XmlElement, allowed: Set<String>) { require(node.attributes.keys.all { it.namespaceURI == W && it.localPart in allowed }) { "Unsupported numbering attribute" } }
    private fun children(node: XmlElement, allowed: Set<String>) {
        require(node.elements().all { it.name.namespaceURI == W && it.name.localPart in allowed }) { "Unsupported numbering feature" }
        require(node.elements().groupBy { it.name.localPart }.all { (name, nodes) -> name in setOf("abstractNum", "num", "lvl", "lvlOverride") || nodes.size == 1 }) { "Duplicate numbering property" }
    }
    private fun XmlElement.one(name: String) = elements().singleOrNull { it.name.namespaceURI == W && it.name.localPart == name }
    private fun XmlElement.value(name: String) = attribute(W, name)
    private companion object { const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main" }
}
