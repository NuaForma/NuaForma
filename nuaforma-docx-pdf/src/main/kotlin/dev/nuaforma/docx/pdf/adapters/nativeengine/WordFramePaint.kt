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
                fillColor(properties, reject, allowAbsent = true)
                properties.elements().filter { it.name.namespaceURI == drawing && it.name.localPart == "ln" }.forEach { strokePaint(it, reject) }
            }
        }
    }

    fun fill(parent: XmlElement, theme: WordThemeColors, reject: (XmlElement, String) -> Nothing): PdfColor? =
        fillColor(parent, reject, allowAbsent = true)?.let { resolve(it, theme) }

    fun stroke(node: XmlElement, theme: WordThemeColors, reject: (XmlElement, String) -> Nothing): PdfBorder? =
        strokePaint(node, reject)?.let { (width, color) -> PdfBorder(width, resolve(color, theme)) }

    private fun fillColor(parent: XmlElement, reject: (XmlElement, String) -> Nothing, allowAbsent: Boolean = false): XmlElement? {
        val fills = parent.elements().filter { it.name.namespaceURI == drawing && it.name.localPart in setOf("solidFill", "noFill") }
        if (fills.isEmpty() && allowAbsent) return null
        if (fills.size != 1) reject(fills.getOrNull(1) ?: parent, "Explicit single frame fill required")
        val node = fills.single()
        if (node.attributes.isNotEmpty() || node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() })
            reject(node, "Unsupported frame fill attribute or text")
        if (node.name.localPart == "noFill") {
            if (node.elements().isNotEmpty()) reject(node, "Invalid frame noFill content")
            return null
        }
        val color = node.elements().singleOrNull() ?: reject(node, "Explicit single frame color required")
        val value = color.attribute("", "val").orEmpty()
        if (color.name.namespaceURI != drawing || color.attributes.size != 1 ||
            color.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() })
            reject(color, "Unsupported original frame RGB color")
        when (color.name.localPart) {
            "prstClr" -> {
                if (color.elements().isNotEmpty()) reject(color, "Frame preset color transformations are not qualified")
                if (value !in setOf("black", "white")) reject(color, "Unsupported original frame preset color")
            }
            "schemeClr" -> validateScheme(color, value, reject)
            "srgbClr" -> {
                if (!value.matches(Regex("[0-9A-Fa-f]{6}"))) reject(color, "Unsupported original frame RGB color")
                for (transform in color.elements()) {
                    if (transform.name.namespaceURI != drawing || transform.name.localPart != "alpha" || transform.attributes.size != 1 ||
                        transform.attribute("", "val") != "100000" || transform.elements().isNotEmpty())
                        reject(transform, "Frame color transformations or transparency are not qualified")
                }
            }
            else -> reject(color, "Unsupported original frame RGB color")
        }
        return color
    }

    private fun validateScheme(color: XmlElement, value: String, reject: (XmlElement, String) -> Nothing) {
        if (WordThemeColors.drawingSlot(value) == null) reject(color, "Unsupported original frame theme color")
        val transforms = color.elements()
        if (transforms.size > 2) reject(transforms[2], "Extra frame theme color transform is not qualified")
        transforms.getOrNull(0)?.let { validateLuminance(it, "lumMod", reject) }
        transforms.getOrNull(1)?.let { validateLuminance(it, "lumOff", reject) }
    }

    private fun validateLuminance(node: XmlElement, name: String, reject: (XmlElement, String) -> Nothing) {
        val raw = node.attribute("", "val").orEmpty()
        if (node.name.namespaceURI != drawing || node.name.localPart != name ||
            node.attributes.size != 1 || !raw.matches(Regex("[0-9]{1,6}")) || raw.toInt() !in 0..100_000 ||
            node.elements().isNotEmpty() || node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() })
            reject(node, "Unsupported frame theme luminance adjustment")
    }

    private fun resolve(color: XmlElement, theme: WordThemeColors): PdfColor {
        val value = requireNotNull(color.attribute("", "val"))
        return when (color.name.localPart) {
            "prstClr" -> if (value == "black") PdfColor(0, 0, 0) else PdfColor(255, 255, 255)
            "schemeClr" -> {
                val transforms = color.elements()
                val modulation = transforms.getOrNull(0)?.attribute("", "val")?.toInt() ?: 100_000
                val offset = transforms.getOrNull(1)?.attribute("", "val")?.toInt() ?: 0
                theme.drawingColor(color, requireNotNull(WordThemeColors.drawingSlot(value)), modulation, offset)
            }
            else -> PdfColor(value.take(2).toInt(16), value.substring(2, 4).toInt(16), value.takeLast(2).toInt(16))
        }
    }

    private fun strokePaint(node: XmlElement, reject: (XmlElement, String) -> Nothing): Pair<Float, XmlElement>? {
        if (node.attributes.keys.any { it.namespaceURI.isNotEmpty() || it.localPart !in setOf("w", "cap", "cmpd", "algn") } ||
            node.attribute("", "cap") !in setOf(null, "flat", "rnd", "sq") || node.attribute("", "cmpd") !in setOf(null, "sng") ||
            node.attribute("", "algn") !in setOf(null, "ctr")) reject(node, "Only centered solid frame strokes are qualified")
        validateStrokeChildren(node, reject)
        val raw = node.attribute("", "w")
        val width = raw?.toLongOrNull()
        if (raw != null && (!raw.matches(Regex("[0-9]{1,10}")) || width == null || width !in 0L..182_880_000L))
            reject(node, "Invalid original frame ln/@w")
        val color = fillColor(node, reject) ?: return null
        if (width == null || width !in 1270L..50_800L)
            reject(node, "Frame ln/@w exceeds the qualified border bounds")
        return width / 12700f to color
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
