package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText
import javax.xml.namespace.QName

/** Identify Word's empty zero-height body paragraphs immediately after a table. */
internal object WordZeroHeightParagraphs {
    private const val WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val paragraph = QName(WORD, "p")
    private val properties = QName(WORD, "pPr")
    private val table = QName(WORD, "tbl")
    private val spacing = QName(WORD, "spacing")
    private val run = QName(WORD, "rPr")
    private val localProperties = setOf(spacing, run)
    private val spacingAttributes = setOf("before", "after", "line", "lineRule")

    fun sourceSpacings(root: XmlElement): Set<XmlElement> {
        if (root.name != QName(WORD, "document")) return emptySet()
        val body = root.elements().singleOrNull { it.name == QName(WORD, "body") } ?: return emptySet()
        return tableFollowers(body).values.toSet()
    }

    fun tableFollowers(body: XmlElement): Map<XmlElement, XmlElement> =
        body.elements().zipWithNext().mapNotNull { (before, after) ->
            if (before.name == table) emptySpacing(after)?.let { after to it } else null
        }.toMap()

    private fun emptySpacing(node: XmlElement): XmlElement? {
        if (node.name != paragraph || node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() }) return null
        val props = node.elements().singleOrNull()?.takeIf { it.name == properties } ?: return null
        val children = props.elements()
        if (children.size != children.map { it.name }.distinct().size || children.any { it.name !in localProperties }) return null
        val metrics = children.singleOrNull { it.name == spacing } ?: return null
        if (metrics.attribute(WORD, "line") != "0" || metrics.attribute(WORD, "lineRule") != "auto" ||
            metrics.attribute(WORD, "before") !in setOf(null, "0") || metrics.attribute(WORD, "after") != "0" ||
            metrics.attributes.keys.any { it.namespaceURI != WORD || it.localPart !in spacingAttributes }) return null
        return metrics
    }
}
