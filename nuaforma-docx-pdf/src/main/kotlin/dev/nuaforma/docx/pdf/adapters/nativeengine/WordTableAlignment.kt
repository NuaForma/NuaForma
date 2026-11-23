package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText
import dev.nuaforma.pdf.PdfTableAlignment

/** Validate alignment before typed binding can replace an unknown enumeration. */
internal object WordTableAlignment {
    private const val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    fun validateSource(root: XmlElement, context: GenerationContext, reject: (XmlElement, String) -> Nothing) {

        fun validate(properties: XmlElement) {
            val values = properties.elements().filter { it.name.localPart == "jc" }
            if (values.size > 1) reject(values[1], "Duplicate original table alignment")
            values.singleOrNull()?.let { read(it, reject) }
        }

        for (node in root.descendants()) {
            context.checkActive()
            if (node.name.namespaceURI != word) continue
            when (node.name.localPart) {
                "tblPr" -> validate(node)
                "tr" -> node.elements().filter { it.name.namespaceURI == word && it.name.localPart == "trPr" }.forEach(::validate)
            }
        }
    }

    fun read(node: XmlElement, reject: (XmlElement, String) -> Nothing): PdfTableAlignment {
        if (node.name.namespaceURI != word || node.name.localPart != "jc" || node.attributes.size != 1 ||
            node.attributes.keys.any { it.namespaceURI != word || it.localPart != "val" } || node.elements().isNotEmpty() ||
            node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() }) reject(node, "Invalid original table alignment")
        return when (node.attribute(word, "val")) {
            "left", "start" -> PdfTableAlignment.LEFT
            "center" -> PdfTableAlignment.CENTER
            "right", "end" -> PdfTableAlignment.RIGHT
            else -> reject(node, "Unsupported original table alignment")
        }
    }

}
