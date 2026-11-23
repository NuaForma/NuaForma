package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText
import dev.nuaforma.pdf.PdfTextBoxAutoFit

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

    fun validate(node: XmlElement, unrotated: Boolean, reject: (XmlElement, String) -> Nothing): PdfTextBoxAutoFit? {
        if (node.attributes.keys.any { it.localPart in setOf("anchorCtr", "upright") && it.namespaceURI.isNotEmpty() })
            reject(node, "Invalid original frame text flag namespace")
        if (node.attribute("", "anchorCtr") !in setOf(null, "0", "false"))
            reject(node, "Unsupported frame bodyPr/@anchorCtr")
        val upright = node.attribute("", "upright")
        if (upright !in setOf(null, "0", "false", "1", "true")) reject(node, "Invalid original frame bodyPr/@upright")
        if (upright in setOf("1", "true") && !unrotated) reject(node, "Upright text in rotated frames is not qualified")

        val fittings = node.elements().filter { it.name.localPart == "spAutoFit" }
        if (fittings.size > 1) reject(fittings[1], "Duplicate shape autofit declaration")
        val fit = fittings.singleOrNull() ?: return null
        if (fit.name.namespaceURI != drawing) reject(fit, "Invalid original shape autofit namespace")
        if (!unrotated || node.attribute("", "rot") != "0" || node.attribute("", "vert") != "horz")
            reject(node, "Shape autofit requires unrotated horizontal text")
        validateFitSource(node, fit, reject)
        return when (node.attribute("", "wrap")) {
            "square" -> PdfTextBoxAutoFit.HEIGHT
            "none" -> PdfTextBoxAutoFit.WIDTH_AND_HEIGHT
            else -> reject(node, "Unsupported shape autofit text wrapping")
        }
    }

    private fun validateFitSource(node: XmlElement, fit: XmlElement, reject: (XmlElement, String) -> Nothing) {
        val geometry = setOf("rot", "vert", "wrap", "lIns", "tIns", "rIns", "bIns", "anchor", "anchorCtr", "upright")
        val defaults = mapOf("spcFirstLastPara" to setOf("0", "false"), "vertOverflow" to setOf("overflow"),
            "horzOverflow" to setOf("overflow"), "numCol" to setOf("1"), "spcCol" to setOf("0"),
            "rtlCol" to setOf("0", "false"), "fromWordArt" to setOf("0", "false"),
            "forceAA" to setOf("0", "false"), "compatLnSpc" to setOf("0", "false", "1", "true"))
        if (node.attributes.any { (key, value) ->
                key.namespaceURI.isNotEmpty() || key.localPart !in geometry && value !in defaults[key.localPart].orEmpty()
            } || node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() })
            reject(node, "Unsupported original shape autofit text policy")
        if (!empty(fit)) reject(fit, "Invalid shape autofit properties")
        val optional = node.elements().filterNot { it === fit }
        if (optional.size > 1) reject(optional[1], "Multiple additional shape autofit properties")
        val warp = optional.singleOrNull() ?: return
        if (warp.name.namespaceURI != drawing || warp.name.localPart != "prstTxWarp" ||
            warp.attributes.size != 1 || warp.attribute("", "prst") != "textNoShape" ||
            warp.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() })
            reject(warp, "Only an unwarped text preset is qualified for shape autofit")
        val adjustments = warp.elements().singleOrNull()
        if (adjustments == null || adjustments.name.namespaceURI != drawing || adjustments.name.localPart != "avLst" || !empty(adjustments))
            reject(warp, "Invalid unwarped autofit adjustment list")
    }

    private fun empty(node: XmlElement) = node.attributes.isEmpty() && node.elements().isEmpty() &&
        node.children.filterIsInstance<XmlText>().none { it.text.isNotBlank() }

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
