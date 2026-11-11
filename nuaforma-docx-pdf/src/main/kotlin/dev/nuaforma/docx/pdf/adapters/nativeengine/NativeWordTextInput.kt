package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText

/** Validate passive editing settings; printing uses the current displayed runs. */
internal object NativeWordTextInput {
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    fun validateSource(root: XmlElement, focus: (XmlElement) -> Unit, reject: (String) -> Nothing) {
        for (data in root.descendants().filter { node -> node.name.localPart == "ffData" &&
            node.elements().any { it.name.localPart == "textInput" } }) validate(data, focus, reject)
    }

    fun validate(data: XmlElement, focus: (XmlElement) -> Unit, reject: (String) -> Nothing) {
        focus(data)
        if (data.name.namespaceURI != W || data.name.localPart != "ffData" || data.attributes.isNotEmpty())
            reject("Invalid text input form metadata")
        children(data, setOf("name", "enabled", "calcOnExit", "textInput"), reject)
        for (node in data.elements().filter { it.name.localPart != "textInput" }) {
            focus(node)
            leaf(node, reject)
            val value = node.attribute(W, "val")
            if (node.name.localPart == "name") {
                if (value != "" && value?.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")) != true) reject("Invalid text input form name")
            } else if (value !in setOf(null, "0", "1", "false", "true", "off", "on")) reject("Invalid text input form flag")
        }
        val input = data.elements().singleOrNull { it.name.localPart == "textInput" }
            ?: reject("Missing regular text input properties")
        focus(input)
        if (input.attributes.isNotEmpty()) reject("Invalid text input properties")
        children(input, setOf("type", "default", "maxLength", "format"), reject)
        for (node in input.elements()) {
            focus(node)
            leaf(node, reject)
            val value = node.attribute(W, "val") ?: reject("Missing text input property value")
            when (node.name.localPart) {
                "type" -> if (value != "regular") reject("Only regular editable text input fields are qualified")
                "maxLength" -> if (value.toIntOrNull()?.let { it in 0..32_767 } != true) reject("Invalid text input maximum length")
                "format" -> if (value.lowercase(java.util.Locale.ROOT) !in setOf("", "uppercase")) reject("Unsupported text input editing format")
                "default" -> if (value.length > 2_048 || value.any { it.isISOControl() }) reject("Invalid text input editing default")
            }
        }
    }

    private fun leaf(node: XmlElement, reject: (String) -> Nothing) {
        children(node, emptySet(), reject)
        if (node.attributes.keys.any { it.namespaceURI != W || it.localPart != "val" }) reject("Invalid text input form attribute")
    }

    private fun children(node: XmlElement, allowed: Set<String>, reject: (String) -> Nothing) {
        val values = node.elements()
        if (values.any { it.name.namespaceURI != W || it.name.localPart !in allowed } ||
            values.map { it.name.localPart }.distinct().size != values.size ||
            node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() }) reject("Unsupported or duplicate text input form content")
    }
}
