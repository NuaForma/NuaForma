package dev.nuaforma.docx.pdf.adapters.ooxml

import dev.nuaforma.docx.pdf.internal.PdfOptions
import javax.xml.namespace.QName

internal object ParagraphFallbacks {
    private const val WORD = NativeTextBoxes.WORD

    fun apply(styles: XmlElement, defaults: PdfOptions.ParagraphDefaults) {
        fun child(parent: XmlElement, local: String): XmlElement = parent.elements().singleOrNull { it.name == QName(WORD, local) }
            ?: XmlElement(QName(WORD, local, "w"), namespaces = linkedMapOf("w" to WORD)).also {
                if (local == "docDefaults") parent.children.add(0, it) else parent.children.add(it)
            }
        val properties = child(child(child(styles, "docDefaults"), "pPrDefault"), "pPr")
        val spacing = child(properties, "spacing")
        if (defaults.afterTwips != null && listOf("after", "afterLines", "afterAutospacing").none { spacing.attribute(WORD, it) != null }) {
            spacing.attributes[QName(WORD, "after", "w")] = defaults.afterTwips.toString()
            spacing.namespaces["w"] = WORD
        }
        if (defaults.line240ths != null && spacing.attribute(WORD, "line") == null && spacing.attribute(WORD, "lineRule") in setOf(null, "auto")) {
            spacing.attributes[QName(WORD, "line", "w")] = defaults.line240ths.toString()
            spacing.attributes[QName(WORD, "lineRule", "w")] = "auto"
            spacing.namespaces["w"] = WORD
        }
    }
}
