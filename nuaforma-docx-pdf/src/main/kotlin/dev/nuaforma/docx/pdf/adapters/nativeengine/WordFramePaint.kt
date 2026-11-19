package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText
import dev.nuaforma.pdf.PdfBorder
import dev.nuaforma.pdf.PdfColor

/** The frame model represents opaque RGB fills and centered solid rectangular borders. */
internal object WordFramePaint {
    private const val wp = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    private const val wps = "http://schemas.microsoft.com/office/word/2010/wordprocessingShape"
    private const val drawing = "http://schemas.openxmlformats.org/drawingml/2006/main"

    fun validateSource(root: XmlElement, context: GenerationContext, reject: (XmlElement, String) -> Nothing) {
        for (anchor in root.descendants()) {
            context.checkActive()
            if (anchor.name.namespaceURI != wp || anchor.name.localPart != "anchor") continue
            for (shape in anchor.descendants().filter { it.name.namespaceURI == wps && it.name.localPart == "wsp" }) {
                if (shape.elements().none { it.name.namespaceURI == wps && it.name.localPart == "txbx" }) continue
                val properties = shape.elements().singleOrNull { it.name.namespaceURI == wps && it.name.localPart == "spPr" } ?: continue
                fill(properties, reject)
                properties.elements().filter { it.name.namespaceURI == drawing && it.name.localPart == "ln" }.forEach { stroke(it, reject) }
            }
        }
    }

    fun fill(parent: XmlElement, reject: (XmlElement, String) -> Nothing): PdfColor? {
        val fills = parent.elements().filter { it.name.namespaceURI == drawing && it.name.localPart in setOf("solidFill", "noFill") }
        if (fills.size != 1) reject(fills.getOrNull(1) ?: parent, "Explicit single frame fill required")
        val node = fills.single()
        if (node.attributes.isNotEmpty() || node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() })
            reject(node, "Unsupported frame fill attribute or text")
        if (node.name.localPart == "noFill") {
            if (node.elements().isNotEmpty()) reject(node, "Invalid frame noFill content")
            return null
        }
        val color = node.elements().singleOrNull() ?: reject(node, "Explicit single frame RGB color required")
        val hex = color.attribute("", "val").orEmpty()
        if (color.name.namespaceURI != drawing || color.name.localPart != "srgbClr" || color.attributes.size != 1 || !hex.matches(Regex("[0-9A-Fa-f]{6}")) ||
            color.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() })
            reject(color, "Unsupported original frame RGB color")
        for (transform in color.elements()) {
            if (transform.name.namespaceURI != drawing || transform.name.localPart != "alpha" || transform.attributes.size != 1 ||
                transform.attribute("", "val") != "100000" || transform.elements().isNotEmpty())
                reject(transform, "Frame color transformations or transparency are not qualified")
        }
        return PdfColor(hex.take(2).toInt(16), hex.substring(2, 4).toInt(16), hex.takeLast(2).toInt(16))
    }

    fun stroke(node: XmlElement, reject: (XmlElement, String) -> Nothing): PdfBorder? {
        if (node.attributes.keys.any { it.namespaceURI.isNotEmpty() || it.localPart !in setOf("w", "cap", "cmpd", "algn") } ||
            node.attribute("", "cap") !in setOf(null, "flat", "rnd", "sq") || node.attribute("", "cmpd") !in setOf(null, "sng") ||
            node.attribute("", "algn") !in setOf(null, "ctr")) reject(node, "Only centered solid frame strokes are qualified")
        validateStrokeChildren(node, reject)
        val raw = node.attribute("", "w")
        val width = raw?.toLongOrNull()
        if (raw != null && (!raw.matches(Regex("[0-9]{1,10}")) || width == null || width !in 0L..182_880_000L))
            reject(node, "Invalid original frame ln/@w")
        val color = fill(node, reject) ?: return null
        if (width == null || width !in 1270L..50_800L)
            reject(node, "Frame ln/@w exceeds the qualified border bounds")
        return PdfBorder(width / 12700f, color)
    }

    private fun validateStrokeChildren(line: XmlElement, reject: (XmlElement, String) -> Nothing) {
        val seen = mutableSetOf<String>()
        for (node in line.elements()) {
            val name = node.name.localPart
            if (node.name.namespaceURI != drawing || name !in setOf("solidFill", "noFill", "miter", "headEnd", "tailEnd", "prstDash") || !seen.add(name))
                reject(node, "Unsupported or duplicate frame stroke feature")
            when (name) {
                "miter" -> validateMiter(node, reject)
                "prstDash" -> if (node.attributes.size != 1 || node.attribute("", "val") != "solid" || node.elements().isNotEmpty())
                    reject(node, "Dashed frame strokes are not qualified")
                "headEnd", "tailEnd" -> validateEnd(node, reject)
            }
        }
    }

    private fun validateMiter(node: XmlElement, reject: (XmlElement, String) -> Nothing) {
        val raw = node.attribute("", "lim")
        val value = raw?.toLongOrNull() ?: if (raw == null) 800_000L else -1L
        if (node.attributes.keys.any { it.namespaceURI.isNotEmpty() || it.localPart != "lim" } || node.elements().isNotEmpty() ||
            value !in 141_422L..2_147_483_647L) reject(node, "Frame miter changes the rectangular border corners")
    }

    private fun validateEnd(node: XmlElement, reject: (XmlElement, String) -> Nothing) {
        if (node.attributes.keys.any { it.namespaceURI.isNotEmpty() || it.localPart !in setOf("type", "w", "len") } ||
            node.attribute("", "type") !in setOf(null, "none") || node.attribute("", "w") !in setOf(null, "sm", "med", "lg") ||
            node.attribute("", "len") !in setOf(null, "sm", "med", "lg") || node.elements().isNotEmpty())
            reject(node, "Frame stroke arrows are not qualified")
    }

}
