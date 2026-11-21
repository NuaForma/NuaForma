package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText

/** Word calculates the printed regions from the actual table geometry and look settings. */
internal class WordTableConditionalMetadata(private val context: GenerationContext) {
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val owners = mapOf("trPr" to "tr", "tcPr" to "tc", "pPr" to "p")
    private val flags = setOf("firstRow", "lastRow", "firstColumn", "lastColumn", "oddVBand", "evenVBand",
        "oddHBand", "evenHBand", "firstRowFirstColumn", "firstRowLastColumn", "lastRowFirstColumn", "lastRowLastColumn")
    private val booleanValues = setOf("1", "0", "true", "false", "on", "off")
    private val mask = Regex("[01]{12}")

    fun inspect(root: XmlElement, forPrint: Boolean, reject: (XmlElement, String) -> Nothing) {
        if (root.name.namespaceURI != word || root.name.localPart !in setOf("document", "hdr", "ftr", "footnotes", "endnotes")) return

        fun scan(node: XmlElement, parent: XmlElement?, insideTable: Boolean) {
            context.checkActive()
            if (insideTable && node.name.namespaceURI == word && parent?.name?.namespaceURI == word &&
                owners[node.name.localPart] == parent.name.localPart) {
                val records = node.elements().filter { it.name.localPart == "cnfStyle" }
                if (records.size > 1) reject(records[1], "Duplicate Word conditional table metadata")
                records.singleOrNull()?.let { record ->
                    validate(record, reject)
                    if (forPrint) node.children.remove(record)
                }
            }
            val table = insideTable || node.name.namespaceURI == word && node.name.localPart == "tbl"
            for (child in node.elements()) scan(child, node, table)
        }

        scan(root, null, false)
    }

    private fun validate(node: XmlElement, reject: (XmlElement, String) -> Nothing) {
        if (node.name.namespaceURI != word || node.elements().isNotEmpty() || node.attributes.isEmpty() ||
            node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() } ||
            node.attributes.any { (name, value) ->
                name.namespaceURI != word || when (name.localPart) {
                    "val" -> !mask.matches(value)
                    in flags -> value !in booleanValues
                    else -> true
                }
            }) reject(node, "Invalid Word conditional table metadata")
    }
}
