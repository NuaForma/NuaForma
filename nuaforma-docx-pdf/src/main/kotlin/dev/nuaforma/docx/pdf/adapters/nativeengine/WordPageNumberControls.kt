package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText
import javax.xml.namespace.QName

/** Keep stored page-field runs inside Word's built-in page-number gallery controls. */
internal object WordPageNumberControls {
    private const val WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val galleries = setOf("Page Numbers (Top of Page)", "Page Numbers (Bottom of Page)")
    private val flags = setOf(null, "0", "1", "false", "true", "off", "on")

    private class Unqualified : RuntimeException(null, null, false, false)

    private data class Replacement(val parent: XmlElement, val control: XmlElement, val content: XmlElement)

    fun normalizeSource(root: XmlElement): Boolean {
        if (!root.isWord("hdr") && !root.isWord("ftr")) return false
        return normalizeChildren(root, inline = false)
    }

    private fun normalizeChildren(parent: XmlElement, inline: Boolean): Boolean {
        var changed = false
        for (child in parent.elements()) {
            if (!inline && child.isWord("p")) {
                changed = normalizeChildren(child, inline = true) || changed
            } else if (child.isWord("sdt")) {
                changed = normalizeGallery(parent, child, inline) || changed
            }
        }
        return changed
    }

    private fun normalizeGallery(parent: XmlElement, control: XmlElement, inline: Boolean): Boolean {
        val replacements = mutableListOf<Replacement>()
        try {
            collectGallery(parent, control, inline, replacements)
        } catch (_: Unqualified) {
            // Leave unfamiliar controls in the source so the native reader diagnoses them in context.
            return false
        }
        for ((owner, original, content) in replacements) {
            val index = owner.children.indexOf(original)
            owner.children.removeAt(index)
            owner.children.addAll(index, content.children)
        }
        return true
    }

    private fun collectGallery(parent: XmlElement, control: XmlElement, inline: Boolean, replacements: MutableList<Replacement>) {
        val content = contentOf(control, inline)
        for (child in content.elements()) {
            if (child.isWord("p")) {
                for (nested in child.elements().filter { it.isWord("sdt") }) {
                    collectGallery(child, nested, inline = true, replacements)
                }
            } else if (child.isWord("sdt")) {
                collectGallery(content, child, inline, replacements)
            }
        }
        replacements += Replacement(parent, control, content)
    }

    private fun contentOf(control: XmlElement, inline: Boolean): XmlElement {
        val properties = control.elements().firstOrNull { it.isWord("sdtPr") } ?: throw Unqualified()
        val objectType = properties.elements().firstOrNull { it.isWord("docPartObj") } ?: throw Unqualified()
        val gallery = objectType.elements().firstOrNull { it.isWord("docPartGallery") } ?: throw Unqualified()
        if (gallery.attribute(WORD, "val") !in galleries) throw Unqualified()
        children(control, setOf("sdtPr", "sdtEndPr", "sdtContent"))
        children(properties, setOf("rPr", "id", "docPartObj"))
        val id = properties.elements().singleOrNull { it.isWord("id") } ?: throw Unqualified()
        leaf(id, setOf("val"))
        if (id.attribute(WORD, "val")?.toIntOrNull() == null) throw Unqualified()
        children(objectType, setOf("docPartGallery", "docPartUnique"))
        leaf(gallery, setOf("val"))
        objectType.elements().singleOrNull { it.isWord("docPartUnique") }?.let {
            leaf(it, setOf("val"))
            if (it.attribute(WORD, "val") !in flags) throw Unqualified()
        }
        properties.elements().singleOrNull { it.isWord("rPr") }?.let { runMetadata(it) }
        control.elements().singleOrNull { it.isWord("sdtEndPr") }?.let {
            children(it, setOf("rPr"))
            it.elements().singleOrNull()?.let { style -> runMetadata(style) }
        }
        val result = control.elements().singleOrNull { it.isWord("sdtContent") }
            ?: throw Unqualified()
        val allowed = if (inline) setOf("r", "sdt", "fldSimple") else setOf("p", "sdt")
        if (result.attributes.isNotEmpty() || result.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() } ||
            result.elements().isEmpty() || result.elements().any { it.name.namespaceURI != WORD || it.name.localPart !in allowed })
            throw Unqualified()
        return result
    }

    private fun runMetadata(node: XmlElement) {
        children(node, setOf("rFonts", "rStyle", "sz", "szCs", "noProof"))
        for (property in node.elements()) when (property.name.localPart) {
            "rFonts" -> {
                leaf(property, setOf("ascii", "hAnsi"))
                if (property.attributes.isEmpty() || property.attributes.values.any { it.isBlank() || it.length > 128 })
                    throw Unqualified()
            }
            "noProof" -> {
                leaf(property, setOf("val"))
                if (property.attribute(WORD, "val") !in flags) throw Unqualified()
            }
            "rStyle" -> {
                leaf(property, setOf("val"))
                val value = property.attribute(WORD, "val")
                if (value.isNullOrBlank() || value.length > 128) throw Unqualified()
            }
            else -> {
                leaf(property, setOf("val"))
                if ((property.attribute(WORD, "val")?.toIntOrNull() ?: 0) <= 0)
                    throw Unqualified()
            }
        }
    }

    private fun children(node: XmlElement, names: Set<String>) {
        val properties = node.elements()
        if (node.attributes.isNotEmpty() || node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() } ||
            properties.any { it.name.namespaceURI != WORD || it.name.localPart !in names } ||
            properties.map { it.name }.distinct().size != properties.size)
            throw Unqualified()
    }

    private fun leaf(node: XmlElement, names: Set<String>) {
        if (node.attributes.keys.any { it.namespaceURI != WORD || it.localPart !in names } ||
            node.elements().isNotEmpty() || node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() })
            throw Unqualified()
    }

    private fun XmlElement.isWord(name: String): Boolean = this.name == QName(WORD, name)

}
