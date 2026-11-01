package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText

/** Editing metadata and compatibility switches whose dependent constructs remain separately qualified. */
internal class WordSettingsMetadata(private val context: GenerationContext) {
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

    fun normalize(parts: Map<String, XmlElement>, failure: (String, XmlElement) -> NativeDocxReadException) {
        normalizeGrids(parts, failure)
        val settings = parts[part] ?: return
        val content = parts.filterKeys { it.startsWith("word/") && it != part }.values
        val hasTables = content.any { root -> root.descendants().any { it.name.namespaceURI == word && it.name.localPart == "tbl" } }
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
                    leaf(child, setOf("val")) { throw failure(part, child) }
                    valid(child.attribute(word, "val")?.matches(Regex("[A-Fa-f0-9]{4}")) == true)
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
                "compat" -> compatibility(child, hasTables) { failure(part, it) }
            }
        }
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

    private fun compatibility(root: XmlElement, hasTables: Boolean, failure: (XmlElement) -> NativeDocxReadException) {
        val names = mutableSetOf<String>()
        for (setting in root.elements()) {
            context.checkActive()
            fun reject(): Nothing = throw failure(setting)
            if (setting.name.namespaceURI != word) continue
            if (setting.name.localPart == "doNotUseHTMLParagraphAutoSpacing") {
                if (!names.add(setting.name.localPart)) reject()
                toggle(setting, word, ::reject)
                root.children.remove(setting)
            } else if (setting.name.localPart == "compatSetting" && setting.attribute(word, "name") in compatibilityNames) {
                namedCompatibility(setting, names, hasTables, ::reject)
                root.children.remove(setting)
            }
        }
    }

    private fun namedCompatibility(setting: XmlElement, names: MutableSet<String>, hasTables: Boolean, reject: () -> Nothing) {
        leaf(setting, setOf("name", "uri", "val"), reject)
        val name = setting.attribute(word, "name")!!
        if (!names.add(name) || setting.attribute(word, "uri") != "http://schemas.microsoft.com/office/word") reject()
        val enabled = boolean(setting.attribute(word, "val") ?: reject(), reject)
        // The reader already uses the ordinary hierarchy, not Word's legacy table-style exceptions.
        if (name == "overrideTableStyleFontSizeAndJustification" && !enabled && hasTables) reject()
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
