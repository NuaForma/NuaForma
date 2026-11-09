package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText

/** Validate original section settings and omit only nonprinting editing protection from the render view. */
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
            section.children.removeIf { it is XmlElement && it.name.namespaceURI == W && it.name.localPart == "formProt" }
        }
    }

    private fun validateSection(section: XmlElement, reject: (XmlElement, String) -> Nothing) {
        for (name in listOf("type", "formProt")) {
            val settings = section.elements().filter { it.name.localPart == name }
            if (settings.size > 1) reject(settings[1], "Duplicate original section property: $name")
            for (setting in settings) {
                if (setting.name.namespaceURI != W || setting.attributes.keys.any { it.namespaceURI != W || it.localPart != "val" } || setting.elements().isNotEmpty() ||
                    setting.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() }) reject(setting, "Invalid original section property: $name")
                val value = setting.attribute(W, "val")
                if (name == "type" && value !in starts || name == "formProt" && value != null && value !in booleans)
                    reject(setting, "Invalid original section property value: $name")
            }
        }
    }
}
