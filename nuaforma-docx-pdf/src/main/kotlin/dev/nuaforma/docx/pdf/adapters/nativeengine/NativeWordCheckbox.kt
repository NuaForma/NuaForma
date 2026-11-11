package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText
import dev.nuaforma.pdf.*

/** Passive legacy form state translated into the existing opaque inline vector model. */
internal object NativeWordCheckbox {
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    fun read(form: NativeDocxFields.Form, pointSize: Float, bold: Boolean, color: PdfColor,
        focus: (XmlElement) -> Unit, reject: (String) -> Nothing): PdfVectorRun {
        val data = form.data
        focus(data)
        if (data.name.namespaceURI != W || data.name.localPart != "ffData" || data.attributes.isNotEmpty()) reject("Invalid checkbox form metadata")
        children(data, setOf("name", "enabled", "calcOnExit", "checkBox"), reject)
        for (node in data.elements().filter { it.name.localPart != "checkBox" }) {
            focus(node)
            leaf(node, setOf("val"), reject)
            if (node.name.localPart == "name") {
                if (node.attribute(W, "val")?.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")) != true) reject("Invalid form field name")
            } else boolean(node, reject)
        }
        val box = data.elements().singleOrNull { it.name.localPart == "checkBox" } ?: reject("Missing checkbox properties")
        focus(box)
        if (box.attributes.isNotEmpty()) reject("Unsupported checkbox properties")
        children(box, setOf("size", "sizeAuto", "default", "checked"), reject)
        val values = box.elements().associateBy { it.name.localPart }
        if ("size" in values && "sizeAuto" in values) reject("Conflicting checkbox sizes")
        for (node in values.values) {
            focus(node)
            leaf(node, if (node.name.localPart == "sizeAuto") emptySet() else setOf("val"), reject)
        }
        val default = values["default"]?.let { boolean(it, reject) } ?: false
        val checked = values["checked"]?.let { boolean(it, reject) } ?: default
        val size = values["size"]?.let { node ->
            node.attribute(W, "val")?.toIntOrNull()?.takeIf { it in 2..400 }?.div(2f) ?: reject("Invalid checkbox size")
        } ?: pointSize
        if (!size.isFinite() || size !in 1f..200f) reject("Unsupported checkbox point size")
        val stroke = if (bold) 85 else 60
        val margin = (stroke + 1) / 2
        val edge = 1000 - margin
        val paths = mutableListOf(PdfVectorPath(listOf(PdfVectorPoint(margin, margin), PdfVectorPoint(edge, margin),
            PdfVectorPoint(edge, edge), PdfVectorPoint(margin, edge)), true))
        if (checked) {
            paths += PdfVectorPath(listOf(PdfVectorPoint(120, 120), PdfVectorPoint(880, 880)))
            paths += PdfVectorPath(listOf(PdfVectorPoint(120, 880), PdfVectorPoint(880, 120)))
        }
        return PdfVectorRun(PdfVector(paths, stroke), size, size, color)
    }

    private fun children(node: XmlElement, allowed: Set<String>, reject: (String) -> Nothing) {
        val elements = node.elements()
        if (elements.any { it.name.namespaceURI != W || it.name.localPart !in allowed } ||
            elements.map { it.name.localPart }.distinct().size != elements.size ||
            node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() }) reject("Unsupported or duplicate checkbox form content")
    }

    private fun leaf(node: XmlElement, allowed: Set<String>, reject: (String) -> Nothing) {
        children(node, emptySet(), reject)
        if (node.attributes.keys.any { it.namespaceURI != W || it.localPart !in allowed }) reject("Unsupported checkbox form attribute")
    }

    private fun boolean(node: XmlElement, reject: (String) -> Nothing): Boolean = when (node.attribute(W, "val")) {
        null, "1", "true", "on" -> true
        "0", "false", "off" -> false
        else -> reject("Invalid checkbox boolean")
    }

}
