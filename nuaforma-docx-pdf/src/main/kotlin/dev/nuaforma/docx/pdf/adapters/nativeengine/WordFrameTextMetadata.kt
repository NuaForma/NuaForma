package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement

/** Inactive text anchoring flags cannot silently change a frame's text placement. */
internal object WordFrameTextMetadata {
    private const val wps = "http://schemas.microsoft.com/office/word/2010/wordprocessingShape"
    private const val drawing = "http://schemas.openxmlformats.org/drawingml/2006/main"

    fun validateSource(root: XmlElement, context: GenerationContext, reject: (XmlElement, String) -> Nothing) {
        for (shape in root.descendants()) {
            context.checkActive()
            if (shape.name.namespaceURI != wps || shape.name.localPart != "wsp" ||
                shape.elements().none { it.name.namespaceURI == wps && it.name.localPart == "txbx" }) continue
            val bodies = shape.elements().filter { it.name.namespaceURI == wps && it.name.localPart == "bodyPr" }
            if (bodies.size != 1) reject(bodies.getOrNull(1) ?: shape, "Missing or duplicate frame bodyPr")
            validate(bodies.single(), unrotated(shape, reject), reject)
        }
    }

    fun validate(node: XmlElement, unrotated: Boolean, reject: (XmlElement, String) -> Nothing) {
        if (node.attributes.keys.any { it.localPart in setOf("anchorCtr", "upright") && it.namespaceURI.isNotEmpty() })
            reject(node, "Invalid original frame text flag namespace")
        if (node.attribute("", "anchorCtr") !in setOf(null, "0", "false"))
            reject(node, "Unsupported frame bodyPr/@anchorCtr")
        val upright = node.attribute("", "upright")
        if (upright !in setOf(null, "0", "false", "1", "true")) reject(node, "Invalid original frame bodyPr/@upright")
        if (upright in setOf("1", "true") && !unrotated) reject(node, "Upright text in rotated frames is not qualified")
    }

    private fun unrotated(shape: XmlElement, reject: (XmlElement, String) -> Nothing): Boolean {
        val properties = shape.elements().singleOrNull { it.name.namespaceURI == wps && it.name.localPart == "spPr" }
        val transform = properties?.elements()?.singleOrNull { it.name.namespaceURI == drawing && it.name.localPart == "xfrm" } ?: return true
        val raw = transform.attribute("", "rot") ?: return true
        val value = raw.toLongOrNull()
        if (!raw.matches(Regex("-?[0-9]{1,10}")) || value == null || value !in -21_600_000L..21_600_000L)
            reject(transform, "Invalid original frame xfrm/@rot")
        return value == 0L
    }

}
