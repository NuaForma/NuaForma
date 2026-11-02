package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText

/** Editing metadata and compatibility switches whose dependent constructs remain separately qualified. */
internal class WordSettingsMetadata(private val context: GenerationContext) {
    data class Formatting(val additiveParagraphSpacing: Boolean = false, val flipLegacyMirrorIndents: Boolean = false,
        val overrideNormalTableStyle: Boolean = false, val openTypeFeatures: Boolean = false)
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val word15 = "http://schemas.microsoft.com/office/word/2012/wordml"
    private val vml = "urn:schemas-microsoft-com:vml"
    private val part = "word/settings.xml"
    private val compatibilityNames = setOf("overrideTableStyleFontSizeAndJustification", "enableOpenTypeFeatures",
        "doNotFlipMirrorIndents", "differentiateMultirowTableHeaders")

    fun normalizeGrid(node: XmlElement, reject: () -> Nothing) {
        leaf(node, setOf("type", "linePitch", "charSpace"), reject)
        if (node.attribute(word, "type") !in setOf(null, "default")) reject()
        for (name in listOf("linePitch", "charSpace")) {
            val value = node.attribute(word, name) ?: continue
            if (value.toIntOrNull() == null) reject()
        }
    }

    fun normalize(parts: Map<String, XmlElement>, failure: (String, XmlElement) -> NativeDocxReadException): Formatting {
        normalizeGrids(parts, failure)
        val settings = parts[part] ?: return Formatting()
        val compatibility = settings.elements().filter { it.name.namespaceURI == word && it.name.localPart == "compat" }
        if (compatibility.size > 1) throw failure(part, compatibility[1])
        var formatting = Formatting()
        val content = parts.filterKeys { it.startsWith("word/") && it != part }.values
        val hasVml = content.any { root -> root.descendants().any { it.name.namespaceURI == vml } }
        for (child in settings.elements()) {
            context.checkActive()

            fun valid(condition: Boolean) { if (!condition) throw failure(part, child) }

            if (child.name.namespaceURI == word15 && child.name.localPart == "chartTrackingRefBased") {
                toggle(child, word15) { throw failure(part, child) }
                settings.children.remove(child)
            } else if (child.name.namespaceURI == word) when (child.name.localPart) {
                "embedSystemFonts", "doNotIncludeSubdocsInStats", "noPunctuationKerning" -> {
                    toggle(child, word) { throw failure(part, child) }
                    settings.children.remove(child)
                }
                "stylePaneFormatFilter" -> {
                    val filters = setOf("allStyles", "alternateStyleNames", "clearFormatting", "customStyles",
                        "directFormattingOnNumbering", "directFormattingOnParagraphs", "directFormattingOnRuns",
                        "directFormattingOnTables", "headingStyles", "latentStyles", "numberingStyles", "stylesInUse",
                        "tableStyles", "top3HeadingStyles", "visibleStyles")
                    leaf(child, filters + "val") { throw failure(part, child) }
                    valid(child.attribute(word, "val")?.matches(Regex("[A-Fa-f0-9]{4}")) != false)
                    valid(child.attributes.filterKeys { it.localPart in filters }.values.all { it in setOf("1", "0", "true", "false", "on", "off") })
                    settings.children.remove(child)
                }
                "documentProtection" -> {
                    protection(child) { throw failure(part, child) }
                    settings.children.remove(child)
                }
                "hdrShapeDefaults" -> {
                    valid(!hasVml && shapeDefaults(child))
                    settings.children.remove(child)
                }
                "compat" -> formatting = compatibility(child) { failure(part, it) }
            }
        }
        return formatting
    }

    private fun normalizeGrids(parts: Map<String, XmlElement>, failure: (String, XmlElement) -> NativeDocxReadException) {
        for ((name, root) in parts) for (section in root.descendants().filter { it.name.namespaceURI == word && it.name.localPart == "sectPr" }) {
            val grids = section.elements().filter { it.name.namespaceURI == word && it.name.localPart == "docGrid" }
            if (grids.size > 1) throw failure(name, grids[1])
            for (grid in grids) {
                context.checkActive()
                normalizeGrid(grid) { throw failure(name, grid) }
                section.children.remove(grid)
            }
        }
    }

    private fun compatibility(root: XmlElement, failure: (XmlElement) -> NativeDocxReadException): Formatting {
        val names = mutableSetOf<String>()
        var additive = false
        var flipMirror = false
        var overrideNormal = false
        var openType = false
        for (setting in root.elements()) {
            context.checkActive()

            fun reject(): Nothing = throw failure(setting)

            if (setting.name.namespaceURI != word) continue
            if (setting.name.localPart == "doNotUseHTMLParagraphAutoSpacing") {
                if (!names.add(setting.name.localPart)) reject()
                toggle(setting, word, ::reject)
                additive = boolean(setting.attribute(word, "val"), ::reject)
                root.children.remove(setting)
            } else if (setting.name.localPart == "compatSetting" && setting.attribute(word, "name") in compatibilityNames) {
                namedCompatibility(setting, names, ::reject)
                if (setting.attribute(word, "name") == "doNotFlipMirrorIndents") flipMirror = !boolean(setting.attribute(word, "val"), ::reject)
                if (setting.attribute(word, "name") == "overrideTableStyleFontSizeAndJustification") overrideNormal = boolean(setting.attribute(word, "val"), ::reject)
                if (setting.attribute(word, "name") == "enableOpenTypeFeatures") openType = boolean(setting.attribute(word, "val"), ::reject)
                root.children.remove(setting)
            }
        }
        return Formatting(additive, flipMirror, overrideNormal, openType)
    }

    private fun namedCompatibility(setting: XmlElement, names: MutableSet<String>, reject: () -> Nothing) {
        leaf(setting, setOf("name", "uri", "val"), reject)
        val name = setting.attribute(word, "name")!!
        if (!names.add(name) || setting.attribute(word, "uri") != "http://schemas.microsoft.com/office/word") reject()
        boolean(setting.attribute(word, "val") ?: reject(), reject)
    }

    private fun protection(node: XmlElement, reject: () -> Nothing) {
        leaf(node, setOf("edit", "formatting", "enforcement", "cryptProviderType", "cryptAlgorithmClass", "cryptAlgorithmType",
            "cryptAlgorithmSid", "cryptSpinCount", "cryptProvider", "algIdExt", "algIdExtSource", "cryptProviderTypeExt",
            "cryptProviderTypeExtSource", "hash", "salt", "algorithmName", "hashValue", "saltValue", "spinCount"), reject)
        if (node.attribute(word, "edit") !in setOf(null, "none", "readOnly", "comments", "trackedChanges", "forms")) reject()
        for (name in listOf("formatting", "enforcement")) node.attribute(word, name)?.let { boolean(it, reject) }
    }

    private fun shapeDefaults(node: XmlElement): Boolean = node.attributes.isEmpty() && node.children.filterIsInstance<XmlText>().all { it.text.isBlank() } && node.descendants().drop(1).all { child ->
        child.name.namespaceURI == "urn:schemas-microsoft-com:office:office" && child.name.localPart in setOf("shapedefaults", "shapelayout", "idmap") &&
            child.attributes.all { (key, _) -> key.namespaceURI in setOf("", vml) && key.localPart in setOf("ext", "spidmax", "data") } &&
            child.children.filterIsInstance<XmlText>().all { it.text.isBlank() }
    }

    private fun leaf(node: XmlElement, attributes: Set<String>, reject: () -> Nothing) {
        if (node.elements().isNotEmpty() || node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() } ||
            node.attributes.keys.any { it.namespaceURI != word || it.localPart !in attributes }) reject()
    }

    private fun toggle(node: XmlElement, namespace: String, reject: () -> Nothing) {
        if (node.elements().isNotEmpty() || node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() } ||
            node.attributes.keys.any { it.namespaceURI !in setOf(word, namespace) || it.localPart != "val" }) reject()
        boolean(node.attribute(namespace, "val") ?: node.attribute(word, "val"), reject)
    }

    private fun boolean(value: String?, reject: () -> Nothing): Boolean = when (value) {
        null, "true", "1", "on" -> true
        "false", "0", "off" -> false
        else -> reject()
    }

}
