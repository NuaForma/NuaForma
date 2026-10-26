package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText

/** Discard only identified authoring/view metadata; effective print properties stay in the reader. */
internal object WordAuthoringMetadata {
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val MC = "http://schemas.openxmlformats.org/markup-compatibility/2006"
    private const val WP14 = "http://schemas.microsoft.com/office/word/2010/wordprocessingDrawing"

    fun normalize(root: XmlElement, part: String, complexText: Boolean) {
        fun reject(): Nothing = throw NativeDocxReadException("Unsupported Word authoring or compatibility settings", part)
        fun XmlElement.value(name: String) = attribute(W, name)
        val nodes = root.descendants().toList()
        for (node in nodes) {
            node.attributes.keys.removeIf { name ->
                name.namespaceURI == MC && name.localPart == "Ignorable" ||
                    name.namespaceURI == W && name.localPart in setOf("rsidR", "rsidRPr", "rsidDel", "rsidP", "rsidSect") ||
                    name.namespaceURI == WP14 && name.localPart in setOf("anchorId", "editId")
            }
            if (node.name.namespaceURI != W) continue
            when (node.name.localPart) {
                "settings" -> {
                    val ignored = setOf("zoom", "rsids", "proofState", "listSeparator", "mathPr", "themeFontLang", "shapeDefaults", "hyphenationZone")
                    for (child in node.elements()) {
                        val name = child.name.localPart
                        if (child.name.namespaceURI != W) {
                            val docId = child.name.namespaceURI in setOf("http://schemas.microsoft.com/office/word/2010/wordml", "http://schemas.microsoft.com/office/word/2012/wordml") && name == "docId"
                            val inactiveMathDefaults = child.name.namespaceURI == "http://schemas.openxmlformats.org/officeDocument/2006/math" && name == "mathPr"
                            if (!docId && !inactiveMathDefaults) reject()
                            node.children.remove(child)
                        } else if (name in ignored) {
                            if (complexText && name == "themeFontLang") reject()
                            node.children.remove(child)
                        } else if (name == "characterSpacingControl") {
                            if (child.value("val") != "doNotCompress") reject()
                            node.children.remove(child)
                        } else if (name == "compat") {
                            for (setting in child.elements()) {
                                if (setting.name.namespaceURI != W || setting.name.localPart != "compatSetting" || setting.value("uri") != "http://schemas.microsoft.com/office/word") reject()
                                val accepted = when (setting.value("name")) {
                                    "compatibilityMode" -> setting.value("val") in setOf("12", "15")
                                    "useWord2013TrackBottomHyphenation" -> setting.value("val") in setOf("0", "1")
                                    else -> false
                                }
                                if (!accepted) reject()
                            }
                            node.children.remove(child)
                        } else if (name == "clrSchemeMapping") {
                            val expected = mapOf("bg1" to "light1", "t1" to "dark1", "bg2" to "light2", "t2" to "dark2") +
                                (1..6).associate { "accent$it" to "accent$it" } + mapOf("hyperlink" to "hyperlink", "followedHyperlink" to "followedHyperlink")
                            if (child.attributes.any { (key, value) -> key.namespaceURI != W || expected[key.localPart] != value }) reject()
                            node.children.remove(child)
                        }
                    }
                }
                "styles" -> node.children.removeIf { it is XmlElement && it.name.namespaceURI == W && it.name.localPart == "latentStyles" }
                "style" -> node.children.removeIf { it is XmlElement && it.name.namespaceURI == W && it.name.localPart in setOf("uiPriority", "qFormat", "semiHidden", "unhideWhenUsed", "locked") }
                "sectPr" -> for (columns in node.elements().filter { it.name.namespaceURI == W && it.name.localPart == "cols" }) {
                    if (columns.elements().isNotEmpty() || columns.value("num") !in setOf(null, "1") || columns.value("equalWidth") !in setOf(null, "1", "true") ||
                        columns.attributes.keys.any { it.namespaceURI != W || it.localPart !in setOf("num", "space", "equalWidth") }) reject()
                    node.children.remove(columns)
                }
                "rPr" -> node.children.removeIf { it is XmlElement && it.name.namespaceURI == W && it.name.localPart == "noProof" }
                "rFonts" -> if (!complexText) node.attributes.keys.removeIf { it.namespaceURI == W && it.localPart in setOf("cs", "eastAsia", "cstheme", "eastAsiaTheme") }
                "lang" -> if (!complexText) node.attributes.keys.removeIf { it.namespaceURI == W && it.localPart in setOf("bidi", "eastAsia") }
                "r" -> node.children.removeIf { it is XmlElement && it.name.namespaceURI == W && it.name.localPart == "lastRenderedPageBreak" }
            }
        }
    }

    fun containsComplexText(roots: Collection<XmlElement>): Boolean = roots.any { root -> root.descendants().any { node ->
        val forced = node.name.namespaceURI == W && node.name.localPart in setOf("rtl", "bidi", "cs") && node.attribute(W, "val") !in setOf("0", "false", "off")
        forced || node.name.namespaceURI == W && node.name.localPart == "t" && node.children.filterIsInstance<XmlText>().any { text ->
            text.text.codePoints().anyMatch { code -> Character.isLetter(code) && Character.UnicodeScript.of(code) !in
                setOf(Character.UnicodeScript.LATIN, Character.UnicodeScript.GREEK, Character.UnicodeScript.CYRILLIC) }
        }
    } }
}
