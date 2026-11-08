package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.*
import javax.xml.namespace.QName

/** Validate unused OPC resources; rendering still uses explicit source styling and caller font bytes. */
internal class WordAuxiliaryParts(private val context: GenerationContext,
    private val failure: (String, XmlElement, String) -> NativeDocxReadException) {
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val office = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private val packageRel = "http://schemas.openxmlformats.org/package/2006/relationships"
    private val drawing = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private val mc = "http://schemas.openxmlformats.org/markup-compatibility/2006"
    private val toggleElements = setOf("b", "i", "bCs", "iCs", "cs", "rtl", "bidi", "strike", "dstrike", "vanish", "webHidden", "noProof",
        "widowControl", "keepNext", "keepLines", "pageBreakBefore", "contextualSpacing", "mirrorIndents", "overflowPunct",
        "suppressAutoHyphens", "autoSpaceDE", "autoSpaceDN", "adjustRightInd", "qFormat", "semiHidden", "unhideWhenUsed", "locked", "autoRedefine")
    private val booleanAttributes = mapOf(
        "style" to setOf("default", "customStyle"),
        "spacing" to setOf("beforeAutospacing", "afterAutospacing"),
        "latentStyles" to setOf("defLockedState", "defSemiHidden", "defUnhideWhenUsed", "defQFormat"),
        "lsdException" to setOf("locked", "semiHidden", "unhideWhenUsed", "qFormat"))
    private val propertyBags = setOf("styles", "style", "docDefaults", "rPrDefault", "pPrDefault", "rPr", "pPr", "numPr",
        "tblPr", "trPr", "tcPr", "pBdr", "tblBorders", "tcBorders")

    fun normalize(parts: MutableMap<String, XmlElement>, names: Set<String>) {
        styles(parts)
        fonts(parts, names)
        theme(parts, names)
    }

    private fun valid(condition: Boolean, part: String, node: XmlElement, reason: String) {
        if (!condition) throw failure(part, node, reason)
    }

    private fun whitespace(node: XmlElement) = node.children.filterIsInstance<XmlText>().all { it.text.isBlank() }

    private fun relationships(root: XmlElement, part: String, hyperlinks: Boolean = false): List<XmlElement> {
        valid(root.name == QName(packageRel, "Relationships") && root.attributes.isEmpty() && whitespace(root), part, root, "Invalid auxiliary relationship root")
        val nodes = root.elements()
        val ids = nodes.map { it.attribute("", "Id") }
        valid(ids.all { !it.isNullOrBlank() } && ids.distinct().size == ids.size, part, root, "Duplicate or missing auxiliary relationship identifiers")
        for (node in nodes) {
            context.checkActive()
            valid(node.name == QName(packageRel, "Relationship") && node.elements().isEmpty() && whitespace(node) &&
                node.attributes.keys.all { it.namespaceURI.isEmpty() && it.localPart in setOf("Id", "Type", "Target", "TargetMode") } &&
                (node.attribute("", "TargetMode") in setOf(null, "Internal") || hyperlinks && node.attribute("", "TargetMode") == "External" &&
                    node.attribute("", "Type") == "$office/hyperlink"), part, node, "External or malformed auxiliary relationship")
        }
        return nodes
    }

    private fun styles(parts: MutableMap<String, XmlElement>) {
        val part = "word/stylesWithEffects.xml"
        val effects = parts[part]
        val mainName = "word/_rels/document.xml.rels"
        val main = parts[mainName]
        val type = "http://schemas.microsoft.com/office/2007/relationships/stylesWithEffects"
        val links = main?.elements().orEmpty().filter { it.attribute("", "Type") == type }
        if (effects == null && links.isEmpty()) return
        val contextNode = effects ?: links.first()
        valid(effects != null && main != null && links.size == 1, part, contextNode, "Incomplete styles-with-effects package graph")
        val link = links.single()
        relationships(requireNotNull(main), mainName, true)
        valid(link.attribute("", "Target") == "stylesWithEffects.xml", mainName, link, "Invalid styles-with-effects target")
        val original = parts["word/styles.xml"] ?: throw failure(part, requireNotNull(effects), "Styles-with-effects requires ordinary style definitions")
        valid(effects!!.name == QName(word, "styles") && original.name == effects.name, part, effects, "Invalid styles-with-effects root")
        val a = original.elements().filter { it.name == QName(word, "style") }
        val b = effects.elements().filter { it.name == QName(word, "style") }
        val first = a.associateBy { it.attribute(word, "styleId") }
        val second = b.associateBy { it.attribute(word, "styleId") }
        valid(first.size == a.size && second.size == b.size && null !in first && null !in second && first.keys.containsAll(second.keys), part, effects, "Ambiguous or divergent styles-with-effects definitions")
        valid(attributes(original) == attributes(effects) && equivalentChildren(original, effects, "style"), part, effects, "Divergent styles-with-effects defaults")
        for ((id, node) in second) valid(equivalent(requireNotNull(first[id]), node), part, node, "Active or divergent styles-with-effects formatting is not qualified")
        val extra = first.keys - second.keys
        val references = setOf("pStyle", "rStyle", "tblStyle", "basedOn", "link", "next", "numStyleLink", "styleLink")
        for (id in extra) {
            val node = requireNotNull(first[id])
            valid(node.attribute(word, "default") in setOf(null, "0", "false", "off"), part, effects, "Missing default style in styles-with-effects")
        }
        if (extra.isNotEmpty()) for (root in parts.values) for (reference in root.descendants()) {
                context.checkActive()
                valid(reference.name.namespaceURI != word || reference.name.localPart !in references || reference.attribute(word, "val") !in extra,
                    part, effects, "Referenced style is missing from styles-with-effects")
        }
        val types = parts["[Content_Types].xml"] ?: throw failure(part, effects, "Missing styles-with-effects content types")
        val declarations = types.elements().filter { it.attribute("", "PartName") == "/$part" }
        valid(declarations.size == 1 && declarations.single().attribute("", "ContentType") == "application/vnd.ms-word.stylesWithEffects+xml", "[Content_Types].xml", types, "Invalid styles-with-effects content type")
        main.children.remove(link)
        parts.remove(part)
    }

    private fun attributes(node: XmlElement): Map<QName, String> {
        val result = node.attributes.filterKeys { it != QName(mc, "Ignorable") }.toMutableMap()
        val toggle = node.name.namespaceURI == word && node.name.localPart in toggleElements
        if (toggle && QName(word, "val") !in result) result[QName(word, "val")] = "true"
        for ((key, value) in result.toMap()) {
            if (node.name.namespaceURI == word && key.namespaceURI == word &&
                (toggle && key.localPart == "val" || key.localPart in booleanAttributes[node.name.localPart].orEmpty())) {
                result[key] = when (value) { "1", "true", "on" -> "true"; "0", "false", "off" -> "false"; else -> value }
            }
        }
        return result
    }

    private fun equivalentChildren(a: XmlElement, b: XmlElement, exclude: String? = null): Boolean {
        fun children(node: XmlElement) = node.children.filterNot {
            it is XmlComment || it is XmlText && it.text.isBlank() || it is XmlElement && it.name == QName(word, exclude.orEmpty())
        }

        fun ordered(node: XmlElement): List<XmlNode> {
            val values = children(node)
            if (node.name.namespaceURI != word || node.name.localPart !in propertyBags || values.any { it !is XmlElement }) return values
            val elements = values.filterIsInstance<XmlElement>()
            if (elements.map { it.name }.distinct().size != elements.size) return values
            return elements.sortedBy { it.name.toString() }
        }

        val first = ordered(a)
        val second = ordered(b)
        return first.size == second.size && first.zip(second).all { (x, y) ->
            when { x is XmlElement && y is XmlElement -> equivalent(x, y); x is XmlText && y is XmlText -> x.text == y.text; else -> false }
        }
    }

    private fun equivalent(a: XmlElement, b: XmlElement): Boolean {
        context.checkActive()
        return a.name == b.name && attributes(a) == attributes(b) && equivalentChildren(a, b)
    }

    private fun fonts(parts: MutableMap<String, XmlElement>, names: Set<String>) {
        val part = "word/_rels/fontTable.xml.rels"
        val root = parts[part]
        val table = parts["word/fontTable.xml"]
        val embeds = table?.descendants().orEmpty().filter { it.name.namespaceURI == word && it.name.localPart in setOf("embedRegular", "embedBold", "embedItalic", "embedBoldItalic") }.toList()
        val files = names.filter { it.startsWith("word/fonts/") }.toSet()
        if (root == null) {
            if (embeds.isNotEmpty() || files.isNotEmpty()) throw failure("word/fontTable.xml", table ?: parts.getValue("word/document.xml"), "Embedded font resources require owned relationships")
            return
        }
        valid(table != null, part, root, "Font relationships require a font table")
        val links = relationships(root, part)
        val used = mutableSetOf<String>()
        for (node in embeds) {
            context.checkActive()
            val id = node.attribute(office, "id")
            valid(node.elements().isEmpty() && whitespace(node) && node.attributes.keys.all {
                it == QName(office, "id") || it.namespaceURI == word && it.localPart in setOf("fontKey", "subsetted") } &&
                node.attribute(word, "subsetted") in setOf(null, "0", "1", "true", "false", "on", "off"), "word/fontTable.xml", node, "Invalid embedded font metadata")
            val link = links.singleOrNull { it.attribute("", "Id") == id } ?: throw failure(part, node, "Missing embedded font relationship")
            val target = link.attribute("", "Target").orEmpty()
            valid(target.matches(Regex("fonts/[A-Za-z0-9_.-]+\\.(?i:odttf|ttf)")) && link.attribute("", "Type") == "$office/font" && "word/$target" in names,
                part, link, "Invalid embedded font target")
            val key = node.attribute(word, "fontKey")
            valid((key == null && target.endsWith(".ttf", true) || key?.matches(Regex("\\{[A-Fa-f0-9]{8}(?:-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}\\}")) == true),
                "word/fontTable.xml", node, "Invalid embedded font obfuscation identity")
            used += requireNotNull(id)
        }
        valid(used == links.map { it.attribute("", "Id") }.toSet() && files == links.map { "word/" + it.attribute("", "Target") }.toSet(),
            part, root, "Unowned embedded font resources")
        val embeddedNodes = embeds.toSet()
        requireNotNull(table).descendants().forEach { parent -> context.checkActive(); parent.children.removeAll(embeddedNodes) }
        parts.remove(part)
    }

    private fun theme(parts: MutableMap<String, XmlElement>, names: Set<String>) {
        val part = "word/theme/_rels/theme1.xml.rels"
        val root = parts[part] ?: return
        val theme = parts["word/theme/theme1.xml"] ?: throw failure(part, root, "Theme relationships require a theme")
        val links = relationships(root, part)
        val blips = theme.descendants().filter { it.name == QName(drawing, "blip") }.toList()
        val ids = mutableSetOf<String>()
        for (node in blips) {
            context.checkActive()
            val id = node.attribute(office, "embed")
            valid(node.attribute(office, "link") == null && id != null, "word/theme/theme1.xml", node, "External theme images are not qualified")
            ids += requireNotNull(id)
        }
        for (link in links) {
            val target = link.attribute("", "Target").orEmpty()
            valid(link.attribute("", "Type") == "$office/image" && target.matches(Regex("\\.\\./media/[A-Za-z0-9_.-]+\\.(?i:png|jpe?g)")) &&
                "word/" + target.removePrefix("../") in names, part, link, "Invalid theme image relationship")
        }
        valid(ids == links.map { it.attribute("", "Id") }.toSet(), part, root, "Unowned or unresolved theme image resources")
        if (links.isNotEmpty()) for ((name, tree) in parts.filterKeys { it != "word/theme/theme1.xml" }) for (node in tree.descendants()) {
            context.checkActive()
            valid(node.name.namespaceURI != drawing || node.name.localPart !in setOf("style", "fillRef", "lnRef", "effectRef"),
                name, node, "Active theme style references are not qualified with theme images")
        }
        parts.remove(part)
    }

}
