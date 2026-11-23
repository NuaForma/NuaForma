package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText
import javax.xml.namespace.QName

/** An explicit inline paragraph frame cancels the saved, floating geometry of its inherited frame. */
internal object WordInlineCellFrame {
    private const val WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val frame = QName(WORD, "framePr")
    private val overlap = QName(WORD, "suppressOverlap")
    private val inline = mapOf("hSpace" to "0", "wrap" to "auto", "vAnchor" to "margin", "yAlign" to "inline")
    private val inherited = mapOf("wrap" to "around", "vAnchor" to "text", "hAnchor" to "text", "y" to "1")

    fun validate(chain: List<XmlElement>, direct: XmlElement?, bodyCell: Boolean, reject: (XmlElement, String) -> Nothing) {
        val frames = chain.flatMap { it.elements().filter { node -> node.name == frame } }
        val overlapRules = chain.flatMap { it.elements().filter { node -> node.name == overlap } }
        val first = (frames + overlapRules).firstOrNull() ?: return
        val ownFrame = direct?.elements()?.singleOrNull { it.name == frame }
        if (!bodyCell || ownFrame == null || attributes(ownFrame) != inline)
            reject(ownFrame ?: first, "Only a fixed inline paragraph frame override in a body table cell is qualified")
        for (node in frames) {
            if (node === ownFrame) continue
            val values = attributes(node)
            if (values == null || values.filterKeys { it != "hSpace" } != inherited ||
                values["hSpace"]?.toIntOrNull() !in 0..1440)
                reject(node, "Unsupported inherited cell frame geometry")
        }
        for (node in overlapRules) {
            val values = attributes(node)
            if (values == null || values.keys.any { it != "val" } ||
                values["val"] !in setOf(null, "0", "1", "false", "true", "off", "on"))
                reject(node, "Invalid cell frame overlap suppression")
        }
        overlapRules.lastOrNull()?.let { effective ->
            if (effective.attribute(WORD, "val") !in setOf("0", "false", "off"))
                reject(effective, "Inline cell frames require inactive overlap suppression")
        }
    }

    private fun attributes(node: XmlElement): Map<String, String>? {
        if (node.elements().isNotEmpty() || node.attributes.keys.any { it.namespaceURI != WORD } ||
            node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() }) return null
        return node.attributes.mapKeys { it.key.localPart }
    }
}
