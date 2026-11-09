package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText

/** Editing metadata and compatibility switches whose dependent constructs remain separately qualified. */
internal class WordSettingsMetadata(private val context: GenerationContext) {
    data class Formatting(val additiveParagraphSpacing: Boolean = false, val flipLegacyMirrorIndents: Boolean = false,
        val overrideNormalTableStyle: Boolean = false, val openTypeFeatures: Boolean = false,
        val automaticHyphenation: Boolean = false, val underlineTrailingSpaces: Boolean? = null)
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val word14 = "http://schemas.microsoft.com/office/word/2010/wordml"
    private val word15 = "http://schemas.microsoft.com/office/word/2012/wordml"
    private val part = "word/settings.xml"
    private val compatibilityNames = setOf("overrideTableStyleFontSizeAndJustification", "enableOpenTypeFeatures",
        "doNotFlipMirrorIndents", "differentiateMultirowTableHeaders")
    private val editorToggles = setOf("removePersonalInformation", "removeDateAndTime", "doNotUseMarginsForDrawingGridOrigin",
        "doNotAutoCompressPictures", "trackRevisions", "hideSpellingErrors", "hideGrammaticalErrors",
        "displayBackgroundShape", "doNotShadeFormData")
    private val drawingMeasures = setOf("drawingGridHorizontalSpacing", "drawingGridVerticalSpacing",
        "drawingGridHorizontalOrigin", "drawingGridVerticalOrigin", "displayHorizontalDrawingGridEvery", "displayVerticalDrawingGridEvery")

    fun normalizeGrid(node: XmlElement, reject: () -> Nothing) {
        leaf(node, setOf("type", "linePitch", "charSpace"), reject)
        if (node.attribute(word, "type") !in setOf(null, "default")) reject()
        for (name in listOf("linePitch", "charSpace")) {
            val value = node.attribute(word, name) ?: continue
            if (value.toIntOrNull() == null) reject()
        }
    }

    fun validateEditingSource(root: XmlElement, reject: (XmlElement) -> Nothing) {
        if (root.name.namespaceURI != word || root.name.localPart != "settings") return
        val dpi = root.elements().filter { it.name.localPart == "defaultImageDpi" }
        if (dpi.size > 1) reject(dpi[1])
        for (node in dpi) {
            context.checkActive()
            imageSaveDpi(node) { reject(node) }
        }
        for (name in editorToggles) {
            context.checkActive()
            val matches = root.elements().filter { it.name.localPart == name }
            if (matches.size > 1) reject(matches[1])
            for (node in matches) {
                if (node.name.namespaceURI != word) reject(node)
                toggle(node, word) { reject(node) }
            }
        }
    }

    private fun imageSaveDpi(node: XmlElement, reject: () -> Nothing) {
        if (node.name.namespaceURI != word14 || node.name.localPart != "defaultImageDpi" ||
            node.elements().isNotEmpty() || node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() } ||
            node.attributes.keys.any { it.namespaceURI != word14 || it.localPart != "val" }) reject()
        val value = node.attribute(word14, "val") ?: reject()
        if (!value.matches(Regex("[+]?[0-9]{1,10}")) || value.toLongOrNull() !in 1L..2_147_483_647L) reject()
    }

    fun validateReadOnlySource(root: XmlElement, reject: (XmlElement) -> Nothing) {
        if (root.name.namespaceURI != word || root.name.localPart != "settings") return
        val nodes = root.elements().filter { it.name.localPart == "writeProtection" }
        if (nodes.size > 1) reject(nodes[1])
        for (node in nodes) {
            context.checkActive()
            if (node.name.namespaceURI != word) reject(node)
            recommendation(node) { reject(node) }
        }
    }

    private fun recommendation(node: XmlElement, reject: () -> Nothing) {
        leaf(node, setOf("recommended"), reject)
        boolean(node.attribute(word, "recommended"), reject)
    }

    fun validateDocumentVariablesSource(root: XmlElement, reject: (XmlElement) -> Nothing) {
        if (root.name.namespaceURI != word || root.name.localPart != "settings") return
        val matches = root.elements().filter { it.name.localPart == "docVars" }
        if (matches.size > 1) reject(matches[1])
        val names = mutableSetOf<String>()
        var total = 0
        for (container in matches) {
            context.checkActive()
            if (container.name.namespaceURI != word || container.attributes.isNotEmpty() ||
                container.children.filterIsInstance<XmlText>().any { !it.text.isBlank() } || container.elements().size > 1_000) reject(container)
            for (node in container.elements()) {
                total += documentVariableSize(node, names, reject)
                if (total > 65_536) reject(node)
            }
        }
    }

    private fun documentVariableSize(node: XmlElement, names: MutableSet<String>, reject: (XmlElement) -> Nothing): Int {
        context.checkActive()
        if (node.name.namespaceURI != word || node.name.localPart != "docVar") reject(node)
        leaf(node, setOf("name", "val")) { reject(node) }
        val name = node.attribute(word, "name") ?: reject(node)
        val value = node.attribute(word, "val") ?: reject(node)
        if (name.isBlank() || name.length > 255 || name.any(Char::isISOControl) ||
            !names.add(name.lowercase(java.util.Locale.ROOT)) || value.length > 16_384) reject(node)
        return name.length + value.length
    }

    fun normalize(parts: Map<String, XmlElement>, failure: (String, XmlElement) -> NativeDocxReadException): Formatting {
        for ((name, root) in parts) WordSectionMetadata.normalizeForms(root) { node, _ -> throw failure(name, node) }
        normalizeGrids(parts, failure)
        normalizeRightIndents(parts, failure)
        val settings = parts[part] ?: return Formatting()
        validateEditingSource(settings) { throw failure(part, it) }
        validateReadOnlySource(settings) { throw failure(part, it) }
        validateDocumentVariablesSource(settings) { throw failure(part, it) }
        settings.children.removeAll(settings.elements().filter { it.name.namespaceURI == word && it.name.localPart == "docVars" }.toSet())
        val compatibility = settings.elements().filter { it.name.namespaceURI == word && it.name.localPart == "compat" }
        if (compatibility.size > 1) throw failure(part, compatibility[1])
        var formatting = Formatting()
        val headerDefaults = settings.elements().filter { it.name.namespaceURI == word && it.name.localPart == "hdrShapeDefaults" }
        if (headerDefaults.size > 1) throw failure(part, headerDefaults[1])
        val editorNames = mutableSetOf<String>()
        val writingStyles = mutableSetOf<Pair<String, String>>()
        val complexText = WordAuthoringMetadata.containsComplexText(parts.values)
        for (child in settings.elements()) {
            context.checkActive()

            fun valid(condition: Boolean) { if (!condition) throw failure(part, child) }

            if (child.name.namespaceURI == word14 && child.name.localPart == "defaultImageDpi") {
                imageSaveDpi(child) { throw failure(part, child) }
                settings.children.remove(child)
            } else if (child.name.namespaceURI == word && child.name.localPart == "stylePaneSortMethod") {
                valid(editorNames.add(child.name.localPart))
                leaf(child, setOf("val")) { throw failure(part, child) }
                valid(child.attribute(word, "val") in setOf("name", "priority", "font", "basedOn", "type", "default", "0000", "0001", "0002", "0003", "0004", "0005"))
                settings.children.remove(child)
            } else if (child.name.namespaceURI == word && child.name.localPart in editorToggles + drawingMeasures) {
                valid(editorNames.add(child.name.localPart))
                if (child.name.localPart in editorToggles) toggle(child, word) { throw failure(part, child) }
                else {
                    leaf(child, setOf("val")) { throw failure(part, child) }
                    val value = child.attribute(word, "val")
                    valid(value != null && value.matches(Regex("[0-9]{1,10}")) && value.toLongOrNull() in 0L..2_147_483_647L)
                    if (child.name.localPart.endsWith("Spacing")) valid(value != "0" && value?.toLongOrNull() != 0L)
                }
                settings.children.remove(child)
            } else if (child.name.namespaceURI == word15 && child.name.localPart == "chartTrackingRefBased") {
                toggle(child, word15) { throw failure(part, child) }
                settings.children.remove(child)
            } else if (child.name.namespaceURI == word) when (child.name.localPart) {
                "activeWritingStyle" -> {
                    leaf(child, setOf("lang", "vendorID", "dllVersion", "nlCheck", "checkStyle", "appName")) { throw failure(part, child) }
                    valid(child.attributes.values.all { it.isNotBlank() && it.length <= 255 && it.none(Char::isISOControl) })
                    val language = child.attribute(word, "lang")
                    valid(language != null && language.matches(Regex("[A-Za-z0-9_-]{1,85}")))
                    val application = child.attribute(word, "appName") ?: ""
                    valid(writingStyles.size < 64 && writingStyles.add(language!! to application))
                    for (name in listOf("nlCheck", "checkStyle")) child.attribute(word, name)?.let { boolean(it) { throw failure(part, child) } }
                    settings.children.remove(child)
                }
                "autoHyphenation" -> {
                    valid(editorNames.add(child.name.localPart))
                    toggle(child, word) { throw failure(part, child) }
                    formatting = formatting.copy(automaticHyphenation = boolean(child.attribute(word, "val")) { throw failure(part, child) })
                    settings.children.remove(child)
                }
                "doNotHyphenateCaps" -> {
                    valid(editorNames.add(child.name.localPart))
                    toggle(child, word) { throw failure(part, child) }
                    settings.children.remove(child)
                }
                "embedTrueTypeFonts", "saveSubsetFonts" -> {
                    valid(editorNames.add(child.name.localPart))
                    toggle(child, word) { throw failure(part, child) }
                    settings.children.remove(child)
                }
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
                "writeProtection" -> {
                    recommendation(child) { throw failure(part, child) }
                    settings.children.remove(child)
                }
                "documentProtection" -> {
                    protection(child) { throw failure(part, child) }
                    settings.children.remove(child)
                }
                "hdrShapeDefaults" -> {
                    WordShapeIdentifiers(context).validate(child) { throw failure(part, child) }
                    settings.children.remove(child)
                }
                "compat" -> {
                    val resolved = compatibility(child, complexText) { failure(part, it) }
                    formatting = resolved.copy(automaticHyphenation = formatting.automaticHyphenation)
                }
            }
        }
        return formatting
    }

    /** Only reached after every section grid has been validated as inactive. */
    private fun normalizeRightIndents(parts: Map<String, XmlElement>, failure: (String, XmlElement) -> NativeDocxReadException) {
        for ((part, root) in parts) for (parent in root.descendants().filter { it.name.namespaceURI == word && it.name.localPart == "pPr" }) {
            val switches = parent.elements().filter { it.name.namespaceURI == word && it.name.localPart == "adjustRightInd" }
            if (switches.size > 1) throw failure(part, switches[1])
            for (node in switches) {
                context.checkActive()
                toggle(node, word) { throw failure(part, node) }
                parent.children.remove(node)
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

    private fun compatibility(root: XmlElement, complexText: Boolean, failure: (XmlElement) -> NativeDocxReadException): Formatting {
        val names = mutableSetOf<String>()
        var additive = false
        var flipMirror = false
        var overrideNormal = false
        var openType = false
        var trailingSpaces: Boolean? = null
        for (setting in root.elements()) {
            context.checkActive()

            fun reject(): Nothing = throw failure(setting)

            if (setting.name.namespaceURI != word) continue
            if (setting.name.localPart in setOf("ulTrailSpace", "useFELayout")) {
                if (!names.add(setting.name.localPart)) reject()
                toggle(setting, word, ::reject)
                val enabled = boolean(setting.attribute(word, "val"), ::reject)
                if (setting.name.localPart == "useFELayout" && enabled && complexText) reject()
                if (setting.name.localPart == "ulTrailSpace") trailingSpaces = enabled
                root.children.remove(setting)
            } else if (setting.name.localPart == "doNotUseHTMLParagraphAutoSpacing") {
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
        return Formatting(additive, flipMirror, overrideNormal, openType, underlineTrailingSpaces = trailingSpaces)
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
