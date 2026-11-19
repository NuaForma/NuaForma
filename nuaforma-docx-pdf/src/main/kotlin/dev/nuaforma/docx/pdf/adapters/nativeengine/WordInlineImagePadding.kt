package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText
import dev.nuaforma.pdf.PdfMargins

/** Drawing extents reserve space around inline pixels; they do not resize or synthesize an effect. */
internal object WordInlineImagePadding {
    private const val wp = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    private val sides = setOf("l", "t", "r", "b")

    fun validateSource(root: XmlElement, context: GenerationContext, reject: (XmlElement, String) -> Nothing) {
        for (node in root.descendants()) {
            context.checkActive()
            if (node.name.namespaceURI == wp && node.name.localPart in setOf("inline", "anchor")) {
                val extents = node.elements().filter { it.name.localPart == "effectExtent" }
                if (extents.size > 1) reject(extents[1], "Duplicate original image extents")
                extents.filter { it.name.namespaceURI != wp }.forEach { reject(it, "Invalid original image extent namespace") }
            }
            if (node.name.namespaceURI == wp && node.name.localPart == "effectExtent") read(node, reject)
        }
    }

    fun read(node: XmlElement, reject: (XmlElement, String) -> Nothing): PdfMargins {
        if (node.name.namespaceURI != wp || node.name.localPart != "effectExtent" || node.attributes.size != 4 ||
            node.attributes.keys.any { it.namespaceURI.isNotEmpty() || it.localPart !in sides } ||
            node.elements().isNotEmpty() || node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() })
            reject(node, "Invalid original image extent structure")

        fun points(side: String): Float {
            val raw = node.attribute("", side).orEmpty()
            val value = raw.toLongOrNull()
            if (!raw.matches(Regex("[0-9]{1,10}")) || value == null || value !in 0L..182_880_000L)
                reject(node, "Invalid original effectExtent/@$side")
            return value / 12_700f
        }

        return PdfMargins(points("t"), points("r"), points("b"), points("l"))
    }

    fun inline(node: XmlElement, reject: (XmlElement, String) -> Nothing): PdfMargins {
        val inline = node.elements().singleOrNull { it.name.namespaceURI == wp && it.name.localPart == "inline" }
        val extents = inline?.elements()?.filter { it.name.namespaceURI == wp && it.name.localPart == "effectExtent" }.orEmpty()
        if (extents.size > 1) reject(extents[1], "Duplicate inline image extents")
        return extents.singleOrNull()?.let { read(it, reject) } ?: PdfMargins(0f, 0f, 0f, 0f)
    }

}
