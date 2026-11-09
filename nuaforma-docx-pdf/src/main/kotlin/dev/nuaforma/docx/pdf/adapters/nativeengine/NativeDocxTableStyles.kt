package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText

/** Source-specific conditional regions become ordinary resolved paragraph/run/cell properties. */
internal class NativeDocxTableStyles(
    private val styles: List<XmlElement>, properties: XmlElement,
    private val rowCount: Int, private val columnCount: Int, private val headerRows: Int,
    private val context: GenerationContext, private val reject: (XmlElement, String) -> Nothing,
) {
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val kinds = listOf("wholeTable", "band1Horz", "band2Horz", "band1Vert", "band2Vert", "firstCol", "lastCol",
        "firstRow", "lastRow", "nwCell", "neCell", "swCell", "seCell")
    private val flags = mapOf("firstRow" to 0x20, "lastRow" to 0x40, "firstColumn" to 0x80,
        "lastColumn" to 0x100, "noHBand" to 0x200, "noVBand" to 0x400)

    private fun child(node: XmlElement, name: String) = node.elements().filter { it.name.namespaceURI == word && it.name.localPart == name }
        .also { if (it.size > 1) reject(node, "Duplicate table style property") }.singleOrNull()

    private fun value(node: XmlElement, name: String) = node.attribute(word, name)

    private fun validate(node: XmlElement, attributes: Set<String>, children: Set<String>) {
        if (node.attributes.keys.any { it.namespaceURI != word || it.localPart !in attributes } ||
            node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() } ||
            node.elements().any { it.name.namespaceURI != word || it.name.localPart !in children })
            reject(node, "Unsupported conditional table style construct")
        for (name in children) child(node, name)
    }

    private fun toggle(node: XmlElement, name: String): Boolean? = when (value(node, name)) {
        null -> null; "1", "true", "on" -> true; "0", "false", "off" -> false
        else -> reject(node, "Invalid table style toggle")
    }

    private val conditional = styles.flatMap { style ->
        context.checkActive()
        child(style, "tcPr")?.let(::validateCell)
        child(style, "tblPr")?.let { validate(it, emptySet(), setOf("tblW", "tblInd", "tblLayout", "tblBorders", "tblCellMar", "tblStyleRowBandSize", "tblStyleColBandSize", "tblLook")) }
        val conditions = style.elements().filter { it.name.namespaceURI == word && it.name.localPart == "tblStylePr" }
        val seen = mutableSetOf<String>()
        conditions.map { region ->
            validate(region, setOf("type"), setOf("pPr", "rPr", "tcPr"))
            val kind = value(region, "type")
            if (kind !in kinds || !seen.add(requireNotNull(kind))) reject(region, "Invalid or duplicate conditional table region")
            child(region, "tcPr")?.let(::validateCell)
            kind to region
        }
    }
    val active: Boolean get() = conditional.any { it.first != "wholeTable" }
    private val tableProperties = styles.mapNotNull { child(it, "tblPr") } + properties

    fun property(name: String): XmlElement? = tableProperties.mapNotNull { child(it, name) }.lastOrNull()

    fun margin(name: String): XmlElement? = tableProperties.mapNotNull { properties ->
        child(properties, "tblCellMar")?.let { margins ->
            validate(margins, emptySet(), setOf("top", "right", "bottom", "left"))
            child(margins, name)
        }
    }.lastOrNull()

    private val look = property("tblLook")?.also { validate(it, flags.keys + "val", emptySet()) }
    private val bitmask = look?.let { node -> value(node, "val")?.let { raw ->
        if (!raw.matches(Regex("[A-Fa-f0-9]{4}"))) reject(node, "Invalid table look mask")
        raw.toInt(16).also { if (it and 0x7e0.inv() != 0) reject(node, "Unsupported table look bits") }
    } } ?: 0x4a0

    private fun flag(name: String) = look?.let { toggle(it, name) } ?: (bitmask and flags.getValue(name) != 0)

    private val rowBand = band("tblStyleRowBandSize")
    private val columnBand = band("tblStyleColBandSize")

    private fun band(name: String): Int = property(name)?.let { node ->
        validate(node, setOf("val"), emptySet())
        value(node, "val")?.toIntOrNull()?.takeIf { it in 0..3 } ?: reject(node, "Unsupported table band size")
    } ?: 0

    private fun validateCell(node: XmlElement) = validate(node, emptySet(), setOf("shd", "vAlign", "tcBorders"))

    private fun bandRegion(index: Int, size: Int, offset: Int, disabled: Boolean, first: String, second: String): String? =
        if (disabled || size == 0) null else if ((index - offset) / size % 2 == 0) first else second

    fun layers(row: Int, column: Int): List<XmlElement> {
        context.checkActive()
        val top = flag("firstRow") && row < maxOf(1, headerRows)
        val bottom = flag("lastRow") && row == rowCount - 1
        val left = flag("firstColumn") && column == 0
        val right = flag("lastColumn") && column == columnCount - 1
        val applicable = listOfNotNull("wholeTable",
            bandRegion(row, rowBand, if (flag("firstRow")) maxOf(1, headerRows) else 0,
                flag("noHBand") || top || bottom, "band1Horz", "band2Horz"),
            bandRegion(column, columnBand, if (flag("firstColumn")) 1 else 0,
                flag("noVBand") || left || right, "band1Vert", "band2Vert"),
            "firstCol".takeIf { left }, "lastCol".takeIf { right },
            "firstRow".takeIf { top }, "lastRow".takeIf { bottom },
            "nwCell".takeIf { top && left }, "neCell".takeIf { top && right },
            "swCell".takeIf { bottom && left }, "seCell".takeIf { bottom && right })
        return styles + applicable.flatMap { name -> conditional.filter { it.first == name }.map { it.second } }
    }

}
