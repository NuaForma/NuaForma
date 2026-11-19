package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText
import dev.nuaforma.pdf.PdfColor
import javax.xml.namespace.QName

/** Highlight and solid text backgrounds retain separate inheritance until the effective color is selected. */
internal object WordRunBackground {
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val colors = mapOf("black" to 0x000000, "blue" to 0x0000FF, "cyan" to 0x00FFFF,
        "green" to 0x00FF00, "magenta" to 0xFF00FF, "red" to 0xFF0000, "yellow" to 0xFFFF00,
        "white" to 0xFFFFFF, "darkBlue" to 0x000080, "darkCyan" to 0x008080, "darkGreen" to 0x008000,
        "darkMagenta" to 0x800080, "darkRed" to 0x800000, "darkYellow" to 0x808000,
        "darkGray" to 0x808080, "lightGray" to 0xC0C0C0)

    fun highlight(node: XmlElement, reject: (XmlElement, String) -> Nothing): PdfColor? {
        val value = node.attribute(W, "val")
        if (node.name != QName(W, "highlight") || node.attributes.keys != setOf(QName(W, "val")) ||
            node.elements().isNotEmpty() || node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() } ||
            value !in colors && value != "none") reject(node, "Invalid text highlight")
        val rgb = colors[value] ?: return null
        return PdfColor(rgb shr 16, rgb shr 8 and 255, rgb and 255)
    }

    /** Qualified against Word exports, including component and gray threshold controls. */
    fun automaticForeground(background: PdfColor?): PdfColor =
        if (background != null && (299 * background.red + 587 * background.green + 114 * background.blue) * 256 < 15_300_000)
            PdfColor(255, 255, 255) else PdfColor(0, 0, 0)

    fun validateSource(root: XmlElement, context: GenerationContext, reject: (XmlElement, String) -> Nothing) {
        for (property in root.descendants().filter { it.name.namespaceURI == W && it.name.localPart in setOf("rPr", "pPr") }) {
            context.checkActive()
            for (name in if (property.name.localPart == "rPr") listOf("highlight", "shd") else listOf("shd")) {
                val nodes = property.elements().filter { it.name.localPart == name }
                if (nodes.size > 1) reject(nodes[1], "Duplicate run background property")
                for (node in nodes) if (name == "highlight") highlight(node, reject)
                    else WordThemeColors.validateRunFill(node) { origin, message ->
                        reject(origin, if (property.name.localPart == "pPr") message.replace("run", "paragraph") else message)
                    }
            }
        }
    }

}
