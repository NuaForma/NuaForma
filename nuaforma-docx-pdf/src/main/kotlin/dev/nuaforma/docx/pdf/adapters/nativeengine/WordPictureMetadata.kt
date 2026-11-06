package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText

/** Only known nonprinting picture metadata is omitted from this request's rendering view. */
internal object WordPictureMetadata {
    private const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val PIC = "http://schemas.openxmlformats.org/drawingml/2006/picture"
    private const val A14 = "http://schemas.microsoft.com/office/drawing/2010/main"
    private const val DPI = "{28A0092B-C50C-407e-A947-70E740481C1C}"
    private val locks = setOf("noGrp", "noSelect", "noRot", "noChangeAspect", "noMove", "noResize", "noEditPoints",
        "noAdjustHandles", "noChangeArrowheads", "noChangeShapeType", "noCrop")
    private val booleans = setOf("0", "1", "false", "true")

    fun normalize(root: XmlElement, reject: (XmlElement, String) -> Nothing) {
        val pictures = root.descendants().filter { it.isName(PIC, "pic") }.toList()
        for (picture in pictures) for (parent in picture.descendants().toList()) {
            when {
                parent.isName(PIC, "cNvPicPr") -> pictureLocks(parent, reject)
                parent.isName(PIC, "spPr") -> noFill(parent, reject)
                parent.isName(A, "blip") -> localDpi(parent, reject)
            }
        }
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
