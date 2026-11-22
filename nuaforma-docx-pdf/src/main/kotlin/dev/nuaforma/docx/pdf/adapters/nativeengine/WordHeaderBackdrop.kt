package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText

internal object WordHeaderBackdrop {
    private const val wps = "http://schemas.microsoft.com/office/word/2010/wordprocessingShape"
    private const val drawing = "http://schemas.openxmlformats.org/drawingml/2006/main"

    fun isUnlabelledShape(shape: XmlElement): Boolean {
        val nonvisual = shape.elements().singleOrNull { it.name.namespaceURI == wps && it.name.localPart == "cNvSpPr" }
            ?: return false
        if (!nonvisual.empty()) return false
        val style = shape.elements().singleOrNull { it.name.namespaceURI == wps && it.name.localPart == "style" } ?: return false
        if (style.attributes.isNotEmpty() || style.hasText()) return false
        val entries = style.elements()
        if (entries.map { it.name.localPart } != listOf("lnRef", "fillRef", "effectRef", "fontRef")) return false
        if (!entries.all { it.isDrawingReference() }) return false
        val body = shape.elements().singleOrNull { it.name.namespaceURI == wps && it.name.localPart == "bodyPr" } ?: return false
        val inert = setOf("rot", "spcFirstLastPara", "vertOverflow", "horzOverflow", "vert", "wrap", "lIns",
            "tIns", "rIns", "bIns", "numCol", "spcCol", "rtlCol", "fromWordArt", "anchor", "anchorCtr", "forceAA", "compatLnSpc")
        if (body.hasText() || body.attributes.keys.any { it.namespaceURI.isNotEmpty() || it.localPart !in inert } ||
            body.attribute("", "rot") != "0" || body.attribute("", "fromWordArt") !in setOf("0", "false")) return false
        val warp = body.elements().firstOrNull() ?: return false
        val adjustment = warp.elements().singleOrNull() ?: return false
        val fit = body.elements().lastOrNull() ?: return false
        return body.elements().size == 2 && warp.name.namespaceURI == drawing && warp.name.localPart == "prstTxWarp" &&
            warp.attributes.size == 1 && warp.attribute("", "prst") == "textNoShape" && !warp.hasText() &&
            adjustment.name.namespaceURI == drawing && adjustment.name.localPart == "avLst" && adjustment.empty() &&
            fit.name.namespaceURI == drawing && fit.name.localPart == "noAutofit" && fit.empty()
    }

    private fun XmlElement.isDrawingReference(): Boolean {
        val index = attribute("", "idx")
        if (name.namespaceURI != drawing || attributes.size != 1 || hasText()) return false
        val validIndex = when (name.localPart) {
            "lnRef", "fillRef" -> (index?.toIntOrNull() ?: -1) in 0..3
            "effectRef" -> index == "0"
            "fontRef" -> index in setOf("major", "minor", "none")
            else -> false
        }
        if (!validIndex) return false
        val color = elements().singleOrNull() ?: return false
        if (color.name.namespaceURI != drawing || color.name.localPart != "schemeClr" || color.attributes.size != 1 ||
            color.attribute("", "val") !in setOf("accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "lt1", "lt2", "dk1", "dk2") ||
            color.hasText() || color.elements().size > 1) return false
        return color.elements().all { shade -> shade.name.namespaceURI == drawing && shade.name.localPart == "shade" &&
            shade.attributes.size == 1 && (shade.attribute("", "val")?.toIntOrNull() ?: -1) in 0..100_000 &&
            shade.elements().isEmpty() && !shade.hasText() }
    }

    private fun XmlElement.empty() = attributes.isEmpty() && elements().isEmpty() && !hasText()

    private fun XmlElement.hasText() = children.filterIsInstance<XmlText>().any { it.text.isNotBlank() }
}
