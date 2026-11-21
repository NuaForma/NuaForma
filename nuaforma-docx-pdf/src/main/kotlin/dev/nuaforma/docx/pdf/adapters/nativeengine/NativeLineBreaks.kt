package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText

/** Validate original controls before typed Word binding can discard invalid enum values. */
internal object NativeLineBreaks {
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    fun validateSource(root: XmlElement, context: GenerationContext, fail: (XmlElement, String) -> Nothing) {
        for (run in root.descendants()) {
            context.checkActive()
            if (run.name.namespaceURI != W || run.name.localPart != "r") continue
            for (control in run.elements().filter { it.name.localPart in setOf("br", "cr", "noBreakHyphen", "softHyphen") }) {
                val allowed = if (control.name.localPart == "br") setOf("type", "clear") else emptySet()
                if (control.name.namespaceURI != W || control.attributes.keys.any { it.namespaceURI != W || it.localPart !in allowed } ||
                    control.children.any { it !is XmlText || it.text.isNotBlank() } ||
                    control.attribute(W, "type") !in setOf(null, "textWrapping", "page", "column") ||
                    control.attribute(W, "clear") !in setOf(null, "none", "left", "right", "all"))
                    fail(control, when (control.name.localPart) {
                        "noBreakHyphen" -> "Invalid original Word nonbreaking hyphen"
                        "softHyphen" -> "Invalid original Word soft hyphen"
                        else -> "Invalid original Word line break control"
                    })
            }
        }
    }
}
