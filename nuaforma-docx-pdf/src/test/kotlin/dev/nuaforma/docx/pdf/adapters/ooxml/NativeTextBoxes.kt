package dev.nuaforma.docx.pdf.adapters.ooxml

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.internal.PdfFailure
import java.math.BigDecimal

/** The measured rectangular profile only. Native text and font properties remain nodes. */
internal class NativeTextBoxes(private val xml: ConversionXml, private val rightMargin: Long, private var nextId: Long) {
    var normalized: Int = 0
        private set

    fun apply(root: XmlElement) {
        // Authored shape-type overrides can change the defaults assumed by the qualified profile.
        if (root.descendants().any { it.name.namespaceURI == VML && it.name.localPart == "shapetype" && it.attribute("", "id") == "_x0000_t202" }) unsupported()
        fun visit(parent: XmlElement) {
            for (index in parent.children.indices) {
                val element = parent.children[index] as? XmlElement ?: continue
                if (element.name.namespaceURI == WORD && element.name.localPart == "pict") {
                    val shapes = element.elements().filter { it.name.namespaceURI == VML && it.name.localPart == "shape" }
                    if (shapes.any { it.attribute("", "type") == "#_x0000_t202" }) {
                        if (element.elements().size != 1 || shapes.size != 1 || element.attributes.isNotEmpty()) unsupported()
                        parent.children[index] = convert(element, shapes.single())
                        continue
                    }
                }
                visit(element)
            }
        }
        visit(root)
    }

    private fun convert(picture: XmlElement, shape: XmlElement): XmlElement {
        if (shape.attributes.keys.any { it.namespaceURI.isNotEmpty() || it.localPart !in setOf("id", "type", "style") }) unsupported()
        val css = style(shape.attribute("", "style") ?: unsupported())
        val textbox = shape.elements().singleOrNull()?.takeIf { it.name.namespaceURI == VML && it.name.localPart == "textbox" } ?: unsupported()
        val content = textbox.elements().singleOrNull()?.takeIf { it.name.namespaceURI == WORD && it.name.localPart == "txbxContent" } ?: unsupported()
        if (shape.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() } || textbox.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() }) unsupported()
        if (textbox.attributes.keys.any { it.namespaceURI.isNotEmpty() || it.localPart !in setOf("inset", "style") }) unsupported()
        val insets = (textbox.attribute("", "inset") ?: unsupported()).split(',').map { emu(it.trim()) }
        if (insets.size != 4) unsupported()
        val margin = textbox.attribute("", "style") != null
        val width: Long
        val height: Long
        if (margin) {
            if (textbox.attribute("", "style") != "mso-layout-flow-alt:bottom-to-top" || insets.any { it != 0L }) unsupported()
            val required = mapOf("position" to "absolute", "rotation" to "90", "mso-position-horizontal" to "right",
                "mso-position-horizontal-relative" to "page", "mso-position-vertical" to "bottom", "mso-position-vertical-relative" to "margin")
            if (required.any { (key, value) -> css[key] != value } || css.keys.any { it !in required.keys + setOf("width", "height", "left", "top", "margin-left", "margin-top") }) unsupported()
            width = emu(css["height"] ?: unsupported())
            height = emu(css["width"] ?: unsupported())
            if (width > rightMargin || rightMargin <= 0) unsupported()
        } else {
            if (css.keys != setOf("width", "height")) unsupported()
            width = emu(css.getValue("width"))
            height = emu(css.getValue("height"))
        }
        if (minOf(width, height) <= 0 || maxOf(width, height) > 100_000_000 || insets[0] + insets[2] >= width || insets[1] + insets[3] >= height) unsupported()
        if (++nextId > Int.MAX_VALUE) unsupported()
        val placement = if (margin) """<wp:anchor distT="0" distB="0" distL="0" distR="0" simplePos="0" relativeHeight="251657728" behindDoc="0" locked="0" layoutInCell="1" allowOverlap="1"><wp:simplePos x="0" y="0"/><wp:positionH relativeFrom="rightMargin"><wp:align>left</wp:align></wp:positionH><wp:positionV relativeFrom="margin"><wp:align>bottom</wp:align></wp:positionV>""" else """<wp:inline distT="0" distB="0" distL="0" distR="0">"""
        val wrap = if (margin) "<wp:wrapNone/>" else ""
        val dash = if (margin) """<a:prstDash val="solid"/>""" else ""
        val body = if (margin) """vert="vert270" wrap="none" lIns="0" tIns="0" rIns="0" bIns="0" anchor="t" anchorCtr="0" """ else """vert="horz" wrap="square" lIns="${insets[0]}" tIns="${insets[1]}" rIns="${insets[2]}" bIns="${insets[3]}" anchor="t" """
        val fragment = """<w:drawing xmlns:w="$WORD" xmlns:wp="$WP" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape">$placement<wp:extent cx="$width" cy="$height"/><wp:effectExtent l="0" t="0" r="0" b="0"/>$wrap<wp:docPr id="$nextId" name="NF conversion text box"/><wp:cNvGraphicFramePr/><a:graphic><a:graphicData uri="http://schemas.microsoft.com/office/word/2010/wordprocessingShape"><wps:wsp><wps:cNvSpPr txBox="1"/><wps:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$width" cy="$height"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill><a:ln w="9525"><a:solidFill><a:srgbClr val="000000"/></a:solidFill>$dash</a:ln></wps:spPr><wps:txbx/><wps:bodyPr $body><a:noAutofit/></wps:bodyPr></wps:wsp></a:graphicData></a:graphic></wp:${if (margin) "anchor" else "inline"}></w:drawing>"""
        val drawing = xml.read(fragment.toByteArray())
        drawing.descendants().single { it.name.localPart == "txbx" }.children.add(content)
        // Removed wrappers may declare prefixes used inside preserved native text.
        val inherited = linkedMapOf<String, String>().apply {
            putAll(picture.namespaces); putAll(shape.namespaces); putAll(textbox.namespaces); putAll(content.namespaces)
        }
        content.namespaces.putAll(inherited)
        normalized++
        return drawing
    }

    private fun style(value: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for (part in value.split(';').filter { it.isNotBlank() }) {
            val pieces = part.split(':', limit = 2)
            if (pieces.size != 2 || result.put(pieces[0].trim(), pieces[1].trim()) != null) unsupported()
        }
        return result
    }

    private fun emu(value: String): Long {
        if (value == "0") return 0
        if (!Regex("[0-9]{1,5}(?:\\.[0-9]{1,6})?pt").matches(value)) unsupported()
        return try { BigDecimal(value.dropLast(2)).multiply(BigDecimal(12700)).longValueExact() } catch (_: ArithmeticException) { unsupported() }
    }

    private fun unsupported(): Nothing = PdfFailure.fail(DiagnosticCodes.UNSUPPORTED_INPUT, RejectionReason.TEXT_BOX_IS_OUTSIDE_THE_VERIFIED_PDF_COMPATIBILITY_PROFILE)

    companion object {
        const val WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        const val VML = "urn:schemas-microsoft-com:vml"
        const val WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    }
}
