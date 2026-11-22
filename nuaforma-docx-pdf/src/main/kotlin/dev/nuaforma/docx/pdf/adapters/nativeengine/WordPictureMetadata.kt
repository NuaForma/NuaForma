package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText
import javax.xml.namespace.QName

/** Only known nonprinting picture metadata is omitted from this request's rendering view. */
internal object WordPictureMetadata {
    private const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val PIC = "http://schemas.openxmlformats.org/drawingml/2006/picture"
    private const val A14 = "http://schemas.microsoft.com/office/drawing/2010/main"
    private const val DPI = "{28A0092B-C50C-407e-A947-70E740481C1C}"
    private const val WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    private const val DECORATIVE = "http://schemas.microsoft.com/office/drawing/2017/decorative"
    private const val DECORATIVE_URI = "{C183D7F6-B498-43B3-948B-1728B52AA6E4}"
    private const val SVG = "http://schemas.microsoft.com/office/drawing/2016/SVG/main"
    private const val SVG_URI = "{96DAC541-7B7A-43D3-8B79-37D633B846F1}"
    private val locks = setOf("noGrp", "noSelect", "noRot", "noChangeAspect", "noMove", "noResize", "noEditPoints",
        "noAdjustHandles", "noChangeArrowheads", "noChangeShapeType", "noCrop")
    private val booleans = setOf("0", "1", "false", "true")

    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private val compressionStates = setOf("none", "email", "screen", "print", "hqprint")

    fun compressionState(name: QName, value: String): Boolean =
        name.namespaceURI.isEmpty() && name.localPart == "cstate" && value in compressionStates

    fun validateSource(root: XmlElement, reject: (XmlElement, String) -> Nothing) {
        if (root.name.namespaceURI != W || root.name.localPart !in setOf("document", "hdr", "ftr", "footnotes", "endnotes")) return
        for (node in root.descendants().filter { it.isName(WP, "docPr") || it.isName(PIC, "cNvPr") }) decorativeList(node, reject)
        for (node in root.descendants().filter { it.isName(A, "blip") }) {
            val valid = node.attributes.all { (name, value) ->
                name.namespaceURI == R && name.localPart == "embed" || compressionState(name, value)
            }
            if (!valid) reject(node, "Unsupported original embedded image attribute or compression state")
            svgReference(node, reject)
        }
    }

    fun normalize(root: XmlElement, reject: (XmlElement, String) -> Nothing) {
        for (properties in root.elements().filter { it.isName(WP, "docPr") }) {
            decorativeList(properties, reject)?.let { properties.children.remove(it) }
        }
        val pictures = root.descendants().filter { it.isName(PIC, "pic") }.toList()
        for (picture in pictures) for (parent in picture.descendants().toList()) {
            when {
                parent.isName(PIC, "cNvPr") -> decorativeList(parent, reject)?.let { parent.children.remove(it) }
                parent.isName(PIC, "cNvPicPr") -> pictureLocks(parent, reject)
                parent.isName(PIC, "spPr") -> noFill(parent, reject)
                parent.isName(A, "blip") -> localDpi(parent, reject)
            }
        }
    }

    private fun decorativeList(parent: XmlElement, reject: (XmlElement, String) -> Nothing): XmlElement? {
        val lists = parent.elements().filter { it.isName(A, "extLst") }
        for (list in lists) {
            val extensions = list.elements()
            val extension = extensions.firstOrNull {
                it.isName(A, "ext") && it.attribute("", "uri")?.equals(DECORATIVE_URI, ignoreCase = true) == true
            } ?: continue
            requireNode(list, lists.size == 1 && extensions.size == 1 && list.attributes.isEmpty() &&
                list.children.filterIsInstance<XmlText>().all { it.text.isBlank() }, reject)
            val flag = extension.elements().singleOrNull()
            requireNode(extension, extension.attributes.size == 1 && extension.children.filterIsInstance<XmlText>().all { it.text.isBlank() } &&
                flag != null && flag.isName(DECORATIVE, "decorative"), reject)
            requireNode(requireNotNull(flag), flag.attributes.size == 1 && flag.attribute("", "val") in booleans && empty(flag), reject)
            return list
        }
        return null
    }

    private fun pictureLocks(parent: XmlElement, reject: (XmlElement, String) -> Nothing) {
        val nodes = parent.elements().filter { it.isName(A, "picLocks") }
        for (node in nodes) {
            requireNode(node, nodes.size == 1 && empty(node) && node.attributes.all { (key, value) ->
                key.namespaceURI.isEmpty() && key.localPart in locks && value in booleans }, reject)
            parent.children.remove(node)
        }
    }

    private fun noFill(parent: XmlElement, reject: (XmlElement, String) -> Nothing) {
        val elements = parent.elements()
        val fills = elements.filter { it.isName(A, "noFill") }
        for (node in fills) {
            requireNode(node, fills.size == 1 && node.attributes.isEmpty() && empty(node), reject)
            parent.children.remove(node)
        }
        for (line in elements.filter { it.isName(A, "ln") }) {
            val fill = line.elements().singleOrNull { it.isName(A, "noFill") }
            if (fill != null && line.elements().size == 1) {
                requireNode(fill, fill.attributes.isEmpty() && empty(fill), reject)
                requireNode(line, line.attributes.all { (key, value) -> key.namespaceURI.isEmpty() && lineAttribute(key.localPart, value) } &&
                    line.children.filterIsInstance<XmlText>().all { it.text.isBlank() }, reject)
                parent.children.remove(line)
            }
        }
    }

    private fun lineAttribute(name: String, value: String): Boolean = when (name) {
        "w" -> value.toLongOrNull()?.let { it in 0..20_116_800L } == true
        "cap" -> value in setOf("rnd", "sq", "flat")
        "cmpd" -> value in setOf("sng", "dbl", "thickThin", "thinThick", "tri")
        "algn" -> value in setOf("ctr", "in")
        else -> false
    }

    data class SvgReference(val relationship: String, private val list: XmlElement, private val extension: XmlElement) {
        fun consume(blip: XmlElement) {
            list.children.remove(extension)
            if (list.elements().isEmpty()) blip.children.remove(list)
        }
    }

    fun svgReference(blip: XmlElement, reject: (XmlElement, String) -> Nothing): SvgReference? {
        val lists = blip.elements().filter { it.isName(A, "extLst") }
        val extensions = lists.flatMap { list -> list.elements().map { list to it } }.filter { (_, entry) ->
            entry.attribute("", "uri")?.equals(SVG_URI, ignoreCase = true) == true ||
                entry.elements().any { it.isName(SVG, "svgBlip") }
        }
        if (extensions.isEmpty()) return null
        val (list, extension) = extensions.first()
        if (extensions.size != 1 || lists.size != 1 || list.attributes.isNotEmpty() ||
            list.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() } ||
            !extension.isName(A, "ext") || extension.attributes.size != 1 ||
            extension.attribute("", "uri")?.equals(SVG_URI, ignoreCase = true) != true ||
            extension.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() })
            reject(extension, "Invalid original SVG picture extension")
        val svg = extension.elements().singleOrNull()
        if (svg == null || !svg.isName(SVG, "svgBlip") || svg.attributes.size != 1 || !empty(svg))
            reject(svg ?: extension, "Invalid original embedded SVG reference")
        val relationship = svg.attribute(R, "embed")?.takeIf { it.isNotBlank() }
            ?: reject(svg, "An SVG picture requires an internal embedded reference")
        val fallback = blip.attribute(R, "embed")
        if (fallback.isNullOrBlank() || fallback == relationship)
            reject(blip, "An SVG picture requires a distinct embedded raster fallback")
        return SvgReference(relationship, list, extension)
    }

    private fun localDpi(parent: XmlElement, reject: (XmlElement, String) -> Nothing) {
        val lists = parent.elements().filter { it.isName(A, "extLst") }
        for (list in lists) {
            val extension = list.elements().singleOrNull() ?: continue
            // Unknown extensions remain in the source view and are rejected by the normal profile.
            if (!extension.isName(A, "ext") || extension.attribute("", "uri")?.equals(DPI, ignoreCase = true) != true) continue
            requireNode(list, lists.size == 1 && list.attributes.isEmpty() &&
                list.children.filterIsInstance<XmlText>().all { it.text.isBlank() }, reject)
            val dpi = extension.elements().singleOrNull()
            requireNode(extension, extension.attributes.size == 1 && extension.children.filterIsInstance<XmlText>().all { it.text.isBlank() } &&
                dpi != null && dpi.isName(A14, "useLocalDpi"), reject)
            requireNode(requireNotNull(dpi), dpi.attributes.size == 1 && dpi.attribute("", "val") in booleans && empty(dpi), reject)
            parent.children.remove(list)
        }
    }

    private fun XmlElement.isName(namespace: String, local: String) = name.namespaceURI == namespace && name.localPart == local

    private fun empty(node: XmlElement) = node.elements().isEmpty() && node.children.filterIsInstance<XmlText>().all { it.text.isBlank() }

    private fun requireNode(node: XmlElement, valid: Boolean, reject: (XmlElement, String) -> Nothing) {
        if (!valid) reject(node, "Invalid passive picture metadata: ${node.name.localPart.take(64)}")
    }

}
