package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText

/** Validate qualified section defaults before omitting nonprinting preferences from the render view. */
internal object WordSectionMetadata {
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val starts = setOf("nextPage", "continuous", "oddPage", "evenPage", "nextColumn")
    private val booleans = setOf("0", "1", "false", "true", "off", "on")

    fun validateSource(root: XmlElement, reject: (XmlElement, String) -> Nothing) {
        for (section in root.descendants().filter { it.name.namespaceURI == W && it.name.localPart == "sectPr" })
            validateSection(section, reject)
    }

    fun normalizeForms(root: XmlElement, reject: (XmlElement, String) -> Nothing) {
        for (section in root.descendants().filter { it.name.namespaceURI == W && it.name.localPart == "sectPr" }) {
            validateSection(section, reject)
            section.children.removeIf { it is XmlElement && it.name.namespaceURI == W && it.name.localPart in setOf("formProt", "paperSrc", "textDirection", "rtlGutter") }
        }
    }

    private fun validateSection(section: XmlElement, reject: (XmlElement, String) -> Nothing) {
        validatePaperSource(section, reject)
        for (name in listOf("type", "formProt", "textDirection", "rtlGutter")) {
            val settings = section.elements().filter { it.name.localPart == name }
            if (settings.size > 1) reject(settings[1], "Duplicate original section property: $name")
            for (setting in settings) {
                if (setting.name.namespaceURI != W || setting.attributes.keys.any { it.namespaceURI != W || it.localPart != "val" } || setting.elements().isNotEmpty() ||
                    setting.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() }) reject(setting, "Invalid original section property: $name")
                val value = setting.attribute(W, "val")
                val valid = when (name) {
                    "type" -> value in starts
                    "textDirection" -> value == "lrTb"
                    else -> value == null || value in booleans
                }
                if (!valid) reject(setting, if (name == "textDirection") "Unsupported original section text direction" else "Invalid original section property value: $name")
            }
        }
    }

    private fun validatePaperSource(section: XmlElement, reject: (XmlElement, String) -> Nothing) {
        val settings = section.elements().filter { it.name.localPart == "paperSrc" }
        if (settings.size > 1) reject(settings[1], "Duplicate original paper source")
        for (setting in settings) {
            if (setting.name.namespaceURI != W || setting.elements().isNotEmpty() ||
                setting.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() } ||
                setting.attributes.any { (key, value) -> key.namespaceURI != W || key.localPart !in setOf("first", "other") ||
                    !value.matches(Regex("[0-9]{1,10}")) || value.toLongOrNull() !in 0L..65_535L })
                reject(setting, "Invalid original printer tray selection")
        }
    }
}
