package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText
import dev.nuaforma.pdf.PdfBorder
import dev.nuaforma.pdf.PdfColor
import dev.nuaforma.pdf.PdfMargins
import dev.nuaforma.pdf.PdfPage
import dev.nuaforma.pdf.PdfPageFrame
import dev.nuaforma.pdf.PdfPageFrameDisplay

/** Bounded physical page frames. A uniform stroke preserves the four mitered corners. */
internal object WordPageBorders {
    private const val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    fun validateSectionSource(section: XmlElement, reject: (XmlElement, String) -> Nothing) {
        val frames = section.elements().filter { it.name.localPart == "pgBorders" }
        if (frames.size > 1) reject(frames[1], "Duplicate original page frame")
        frames.singleOrNull()?.let { parse(it, reject) }
    }

    fun read(root: XmlElement, page: PdfPage, reject: (XmlElement, String) -> Nothing): PdfPageFrame {
        val frame = parse(root, reject)
        val (top, right, bottom, left) = frame.offsets
        if (top + bottom + 2 * frame.border.width >= page.height ||
            left + right + 2 * frame.border.width >= page.width)
            reject(root, "Page frame leaves no interior")
        return frame
    }

    private fun parse(root: XmlElement, reject: (XmlElement, String) -> Nothing): PdfPageFrame {
        if (!shape(root, setOf("offsetFrom", "display", "zOrder")) ||
            root.attribute(word, "offsetFrom") != "page" || root.attribute(word, "zOrder") !in setOf(null, "front"))
            reject(root, "Only a foreground frame measured from physical page edges is qualified")
        val display = when (root.attribute(word, "display")) {
            null, "allPages" -> PdfPageFrameDisplay.ALL
            "firstPage" -> PdfPageFrameDisplay.FIRST
            "notFirstPage" -> PdfPageFrameDisplay.EXCEPT_FIRST
            else -> reject(root, "Unsupported page frame display")
        }
        val sides = listOf("top", "right", "bottom", "left")
        val elements = root.elements()
        if (elements.size != sides.size || elements.any { it.name.namespaceURI != word } ||
            elements.map { it.name.localPart }.toSet() != sides.toSet())
            reject(root, "Page frame requires four distinct sides")
        val edges = sides.map { side ->
            val target = elements.single { it.name.localPart == side }
            if (!shape(target, setOf("val", "sz", "space", "color")) || target.elements().isNotEmpty() ||
                target.attribute(word, "val") != "single")
                reject(target, "Only a solid page-frame side is qualified")
            val size = target.attribute(word, "sz")?.toIntOrNull()?.takeIf { it in 2..32 }
                ?: reject(target, "Invalid page-frame width")
            val offset = target.attribute(word, "space")?.toIntOrNull()?.takeIf { it in 0..31 }
                ?: reject(target, "Invalid page-frame offset")
            val setting = target.attribute(word, "color") ?: "auto"
            val rgb = if (setting == "auto") 0 else setting.takeIf { it.matches(Regex("[0-9A-Fa-f]{6}")) }?.toInt(16)
                ?: reject(target, "Invalid page-frame color")
            offset.toFloat() to PdfBorder(size / 8f, PdfColor(rgb shr 16, (rgb shr 8) and 255, rgb and 255))
        }
        val border = edges.first().second
        if (edges.any { it.second != border }) reject(root, "Page-frame stroke must be uniform at the corners")
        val offsets = edges.map { it.first }
        return PdfPageFrame(PdfMargins(offsets[0], offsets[1], offsets[2], offsets[3]), border, display)
    }

    private fun shape(node: XmlElement, attributes: Set<String>): Boolean =
        node.name.namespaceURI == word && node.children.filterIsInstance<XmlText>().none { it.text.isNotBlank() } &&
            node.attributes.keys.all { it.namespaceURI == word && it.localPart in attributes }

}
