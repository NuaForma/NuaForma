package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.docx.pdf.adapters.ooxml.*
import dev.nuaforma.pdf.*

/** Small structural grammar for passive inline polylines, without names or private trust markers. */
internal class NativeDocxVectors(private val focus: (XmlElement) -> Unit, private val reject: (String) -> Nothing) {
    private val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val wp = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    private val a = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private val wps = "http://schemas.microsoft.com/office/word/2010/wordprocessingShape"

    fun isVector(node: XmlElement): Boolean {
        val inline = child(node, wp, "inline") ?: return false
        val graphic = child(inline, a, "graphic") ?: return false
        val data = child(graphic, a, "graphicData") ?: return false
        val drawingShape = child(data, wps, "wsp") ?: return false
        val properties = child(drawingShape, wps, "spPr") ?: return false
        return child(properties, a, "custGeom") != null
    }

    private fun child(node: XmlElement, namespace: String, name: String): XmlElement? =
        node.elements().singleOrNull { it.name.namespaceURI == namespace && it.name.localPart == name }


    fun read(node: XmlElement): PdfVectorRun {
        shape(node, w, "drawing", emptyMap(), wp to "inline")
        val inline = node.elements().single()
        shape(inline, wp, "inline", mapOf("distT" to "0", "distB" to "0", "distL" to "0", "distR" to "0"),
            wp to "extent", wp to "docPr", a to "graphic")
        val (extent, docPr, graphic) = inline.elements()
        attributes(extent, wp, "extent", setOf("cx", "cy"))
        val size = integer(extent.attribute("", "cx"), 1270..12_700_000)
        requireThat(extent.attribute("", "cy") == size.toString(), "Inline vector must be square")
        attributes(docPr, wp, "docPr", setOf("id", "name"))
        integer(docPr.attribute("", "id"), 1..Int.MAX_VALUE)
        requireThat(docPr.attribute("", "name")?.length in 1..128, "Invalid vector drawing name")
        shape(graphic, a, "graphic", emptyMap(), a to "graphicData")
        val data = graphic.elements().single()
        shape(data, a, "graphicData", mapOf("uri" to wps), wps to "wsp")
        val drawingShape = data.elements().single()
        shape(drawingShape, wps, "wsp", emptyMap(), wps to "cNvSpPr", wps to "spPr", wps to "bodyPr")
        shape(drawingShape.elements()[0], wps, "cNvSpPr", emptyMap())
        shape(drawingShape.elements()[2], wps, "bodyPr", emptyMap())
        val props = drawingShape.elements()[1]
        shape(props, wps, "spPr", emptyMap(), a to "xfrm", a to "custGeom", a to "noFill", a to "ln")
        val transform = props.elements()[0]
        shape(transform, a, "xfrm", emptyMap(), a to "off", a to "ext")
        shape(transform.elements()[0], a, "off", mapOf("x" to "0", "y" to "0"))
        shape(transform.elements()[1], a, "ext", mapOf("cx" to "$size", "cy" to "$size"))
        shape(props.elements()[2], a, "noFill", emptyMap())
        val paths = geometry(props.elements()[1])
        val (width, color) = stroke(props.elements()[3], size)
        return guarded { PdfVectorRun(PdfVector(paths, width), size / 12700f, size / 12700f, color) }
    }

    private fun geometry(geometry: XmlElement): List<PdfVectorPath> {
        shape(geometry, a, "custGeom", emptyMap(), a to "avLst", a to "gdLst", a to "ahLst", a to "cxnLst", a to "rect", a to "pathLst")
        for (i in 0..3) shape(geometry.elements()[i], a, listOf("avLst", "gdLst", "ahLst", "cxnLst")[i], emptyMap())
        shape(geometry.elements()[4], a, "rect", mapOf("l" to "0", "t" to "0", "r" to "1000", "b" to "1000"))
        val list = geometry.elements()[5]
        requireThat(list.elements().size in 1..8, "Inline vector path budget exceeded")
        attributes(list, a, "pathLst", emptySet(), leaf = false)
        return list.elements().map(::path)
    }

    private fun path(path: XmlElement): PdfVectorPath {
        attributes(path, a, "path", setOf("w", "h", "fill", "stroke", "extrusionOk"), leaf = false)
        requireThat(path.attribute("", "w") == "1000" && path.attribute("", "h") == "1000" && path.attribute("", "fill") == "none" &&
            path.attribute("", "stroke") in setOf("1", "true") && path.attribute("", "extrusionOk") in setOf("0", "false"), "Unsupported vector path properties")
        val commands = path.elements()
        val closed = commands.lastOrNull()?.name?.localPart == "close"
        if (closed) shape(commands.last(), a, "close", emptyMap())
        val positions = if (closed) commands.dropLast(1) else commands
        requireThat(positions.size in (if (closed) 3 else 2)..16, "Inline vector point budget exceeded")
        val points = positions.mapIndexed { index, command ->
            shape(command, a, if (index == 0) "moveTo" else "lnTo", emptyMap(), a to "pt")
            val point = command.elements().single()
            attributes(point, a, "pt", setOf("x", "y"))
            PdfVectorPoint(integer(point.attribute("", "x"), 0..1000), integer(point.attribute("", "y"), 0..1000))
        }
        return guarded { PdfVectorPath(points, closed) }
    }

    private fun stroke(line: XmlElement, size: Int): Pair<Int, PdfColor> {
        attributes(line, a, "ln", setOf("w", "cap", "cmpd", "algn"), leaf = false)
        requireThat(line.attribute("", "cap") == "rnd" && line.attribute("", "cmpd") == "sng" && line.attribute("", "algn") == "ctr", "Unsupported vector stroke")
        requireThat(line.elements().map { it.name.namespaceURI to it.name.localPart } == listOf(a to "solidFill", a to "prstDash", a to "round"), "Unsupported vector stroke content")
        val width = integer(line.attribute("", "w"), 1..1_270_000)
        val stroke = kotlin.math.round(width * 1000.0 / size).toInt()
        requireThat(kotlin.math.abs(width - size * stroke / 1000.0) <= .51, "Unsupported vector stroke precision")
        val fill = line.elements()[0]
        shape(fill, a, "solidFill", emptyMap(), a to "srgbClr")
        val rgb = fill.elements().single()
        attributes(rgb, a, "srgbClr", setOf("val"))
        val value = rgb.attribute("", "val") ?: reject("Missing vector color")
        requireThat(value.matches(Regex("[0-9A-Fa-f]{6}")), "Invalid vector color")
        shape(line.elements()[1], a, "prstDash", mapOf("val" to "solid"))
        shape(line.elements()[2], a, "round", emptyMap())
        val color = PdfColor(value.substring(0, 2).toInt(16), value.substring(2, 4).toInt(16), value.substring(4, 6).toInt(16))
        return stroke to color
    }

    private fun shape(node: XmlElement, ns: String, name: String, attrs: Map<String, String>, vararg children: Pair<String, String>) {
        attributes(node, ns, name, attrs.keys, leaf = false)
        requireThat(attrs.all { node.attribute("", it.key) == it.value }, "Unsupported inline vector property")
        requireThat(node.elements().map { it.name.namespaceURI to it.name.localPart } == children.toList(), "Unsupported inline vector structure")
    }

    private fun attributes(node: XmlElement, ns: String, name: String, allowed: Set<String>, leaf: Boolean = true) {
        focus(node)
        requireThat(node.name.namespaceURI == ns && node.name.localPart == name && node.attributes.size == allowed.size &&
            node.attributes.keys.all { it.namespaceURI.isEmpty() && it.localPart in allowed }, "Unsupported inline vector element or attribute")
        requireThat(node.children.none { it is XmlText && it.text.isNotBlank() }, "Unexpected inline vector text")
        if (leaf) requireThat(node.elements().isEmpty(), "Unsupported inline vector child")
    }

    private fun integer(value: String?, range: IntRange): Int {
        requireThat(value != null && value.matches(Regex("0|[1-9][0-9]{0,9}")), "Invalid inline vector coordinate")
        return value?.toIntOrNull()?.takeIf { it in range } ?: reject("Inline vector coordinate exceeds its bounds")
    }

    private fun <T> guarded(action: () -> T): T = try { action() } catch (_: IllegalArgumentException) {
        reject("Invalid or excessive inline vector geometry")
    }

    private fun requireThat(condition: Boolean, message: String) {
        if (!condition) reject(message)
    }
}
