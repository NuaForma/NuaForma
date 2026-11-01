package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.docx.pdf.adapters.ooxml.*
import dev.nuaforma.pdf.*

/** Strict, passive line geometry. It never follows a drawing relationship. */
internal class NativeDocxLines(private val focus: (XmlElement) -> Unit, private val reject: (String) -> Nothing) {
    private val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val a = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private val wp = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    private val v = "urn:schemas-microsoft-com:vml"
    private val wps = "http://schemas.microsoft.com/office/word/2010/wordprocessingShape"

    fun isLine(node: XmlElement): Boolean = node.descendants().any {
        it.name.namespaceURI == v && it.name.localPart == "line" ||
            it.name.namespaceURI == a && it.name.localPart == "prstGeom" && it.attribute("", "prst") == "line"
    }

    fun borders(node: XmlElement, initial: PdfParagraphBorders?): PdfParagraphBorders {
        focus(node); attrs(node, emptySet(), w)
        requireThat(node.elements().all { it.name.namespaceURI == w && it.name.localPart in setOf("top", "right", "bottom", "left", "between") }, "Unsupported paragraph border edge")
        requireThat(node.elements().map { it.name.localPart }.distinct().size == node.elements().size, "Duplicate paragraph border edge")
        fun edge(name: String, inherited: PdfParagraphEdge?): PdfParagraphEdge? {
            val e = node.elements().singleOrNull { it.name.localPart == name } ?: return inherited
            focus(e); attrs(e, setOf("val", "sz", "space", "color"), w)
            requireThat(e.elements().isEmpty(), "Unsupported paragraph border content")
            if (e.attribute(w, "val") in setOf("nil", "none")) return null
            val style = style(e.attribute(w, "val"))
            val width = numeric(e.attribute(w, "sz"), 8f) / 8
            return PdfParagraphEdge(PdfLineStroke(width, color(e.attribute(w, "color") ?: "auto"), style), numeric(e.attribute(w, "space"), 0f))
        }
        return PdfParagraphBorders(edge("top", initial?.top), edge("right", initial?.right), edge("bottom", initial?.bottom), edge("left", initial?.left), edge("between", initial?.between))
    }

    fun drawing(node: XmlElement, page: PdfPage): PdfLineDrawing {
        focus(node)
        return if (node.name.localPart == "pict") vml(node, page) else drawingMl(node, page)
    }

    private fun vml(node: XmlElement, page: PdfPage): PdfLineDrawing {
        requireThat(node.elements().size == 1, "A line container requires one line")
        val line = node.elements().single(); focus(line)
        requireThat(line.name.namespaceURI == v && line.name.localPart == "line", "Unsupported VML line geometry")
        attrs(line, setOf("id", "style", "from", "to", "strokecolor", "strokeweight"))
        val entries = (line.attribute("", "style") ?: "").split(';').filter { it.isNotBlank() }.map {
            val pair = it.split(':', limit = 2); requireThat(pair.size == 2, "Invalid line style"); pair[0].trim() to pair[1].trim()
        }
        val css = entries.toMap()
        requireThat(css.size == entries.size && css.keys.all { it in setOf("position", "z-index", "margin-left", "margin-top", "mso-position-horizontal-relative", "mso-position-vertical-relative") }, "Unsupported line positioning or wrapping")
        requireThat(css["position"] == "absolute", "Only anchored lines are qualified")
        val horizontal = css["mso-position-horizontal-relative"] ?: "margin"
        val vertical = css["mso-position-vertical-relative"] ?: "text"
        requireThat(horizontal in setOf("page", "margin", "text") && vertical in setOf("page", "margin", "text"), "Unsupported line anchor")
        val fixedX = horizontal != "text"
        val fixedY = vertical != "text"
        val x = (if (horizontal == "margin") page.margins.left else 0f) + length(css["margin-left"] ?: "0pt")
        val y = (if (vertical == "margin") page.margins.top else 0f) + length(css["margin-top"] ?: "0pt")
        fun point(name: String): Pair<Float, Float> {
            val parts = (line.attribute("", name) ?: reject("Missing line endpoint")).split(',')
            requireThat(parts.size == 2, "Invalid line endpoint")
            return length(parts[0]) + x to length(parts[1]) + y
        }
        val from = point("from"); val to = point("to")
        requireThat(line.elements().size <= 1 && line.elements().all { it.name.namespaceURI == v && it.name.localPart == "stroke" }, "Unsupported line effect")
        val stroke = line.elements().singleOrNull()
        stroke?.let { focus(it); attrs(it, setOf("dashstyle")); requireThat(it.elements().isEmpty(), "Unsupported line stroke") }
        val dash = when (stroke?.attribute("", "dashstyle")) {
            null, "solid" -> PdfLineStyle.SOLID; "dash", "shortDash" -> PdfLineStyle.DASHED; "shortDot" -> PdfLineStyle.DOTTED
            else -> reject("Unsupported VML line dash")
        }
        val weight = length(line.attribute("", "strokeweight") ?: ".75pt")
        val z = css["z-index"]?.toLongOrNull() ?: 0L
        val pattern = stroke?.attribute("", "dashstyle")
        val on = when (pattern) { "dash" -> 4 * weight; "shortDash" -> 3 * weight; "shortDot" -> weight; else -> null }
        val off = when (pattern) { "dash" -> 3 * weight; "shortDash", "shortDot" -> weight; else -> null }
        return PdfLineDrawing(from.first, from.second, to.first, to.second,
            PdfLineStroke(weight, color(line.attribute("", "strokecolor") ?: "black"), dash, on, off), fixedX, fixedY, z < 0)
    }

    private fun drawingMl(node: XmlElement, page: PdfPage): PdfLineDrawing {
        requireThat(node.elements().size == 1, "Invalid line drawing container")
        val anchor = node.elements().single(); focus(anchor)
        requireThat(anchor.name.namespaceURI == wp && anchor.name.localPart == "anchor", "Only anchored drawing lines are qualified")
        attrs(anchor, setOf("distT", "distB", "distL", "distR", "simplePos", "relativeHeight", "behindDoc", "locked", "layoutInCell", "allowOverlap"))
        requireThat(anchor.attribute("", "simplePos") in setOf(null, "0", "false"), "Simple-position lines are not qualified")
        requireThat(anchor.attribute("", "behindDoc") in setOf(null, "0", "1", "false", "true"), "Invalid line layer")
        requireThat(anchor.attribute("", "layoutInCell") in setOf(null, "1", "true"), "Lines escaping their owning cell are not qualified")
        val allowed = mapOf(wp to setOf("anchor", "simplePos", "positionH", "positionV", "posOffset", "extent", "effectExtent", "wrapNone", "docPr", "cNvGraphicFramePr"),
            a to setOf("graphic", "graphicData", "graphicFrameLocks", "xfrm", "off", "ext", "prstGeom", "avLst", "ln", "solidFill", "srgbClr", "prstDash", "headEnd", "tailEnd", "noFill"),
            wps to setOf("wsp", "cNvSpPr", "cNvPr", "spPr", "bodyPr"))
        requireThat(anchor.descendants().all { it.name.localPart in allowed[it.name.namespaceURI].orEmpty() }, "Unsupported line effect, theme or geometry")
        val all = anchor.descendants().toList()
        fun one(ns: String, name: String): XmlElement = all.singleOrNull { it.name.namespaceURI == ns && it.name.localPart == name } ?: reject("Missing or duplicate line $name")
        requireThat(anchor.elements().count { it.name.namespaceURI == wp && it.name.localPart == "wrapNone" } == 1, "Only non-wrapping lines are qualified")
        one(a, "graphicData").let { requireThat(it.attribute("", "uri") == wps, "Unsupported line graphic payload") }
        one(a, "prstGeom").let { requireThat(it.attribute("", "prst") == "line" && it.elements().all { child -> child.name.localPart == "avLst" && child.elements().isEmpty() }, "Unsupported line preset") }
        all.filter { it.name.localPart in setOf("headEnd", "tailEnd") }.forEach { end ->
            focus(end); attrs(end, setOf("type", "w", "len")); requireThat(end.attribute("", "type") in setOf(null, "none"), "Line arrows are not qualified")
        }
        all.filter { it.name.localPart == "effectExtent" }.forEach { extent -> requireThat(extent.attributes.values.all { it == "0" }, "Line effects are not qualified") }
        val transform = one(a, "xfrm"); focus(transform); attrs(transform, setOf("rot", "flipH", "flipV"))
        for (flip in listOf("flipH", "flipV")) requireThat(transform.attribute("", flip) in setOf(null, "0", "1", "false", "true"), "Invalid line reflection")
        requireThat(transform.elements().all { it.name.localPart in setOf("off", "ext") }, "Unsupported line transform")
        val origin = one(a, "off")
        requireThat(origin.attribute("", "x") == "0" && origin.attribute("", "y") == "0", "Shifted local line transforms are not qualified")
        val extent = one(wp, "extent")
        val width = numeric(extent.attribute("", "cx")) / 12700; val height = numeric(extent.attribute("", "cy")) / 12700
        val localExtent = one(a, "ext")
        requireThat(numeric(localExtent.attribute("", "cx")) / 12700 == width && numeric(localExtent.attribute("", "cy")) / 12700 == height, "Scaled line geometry is not qualified")
        fun position(name: String, horizontal: Boolean): Pair<Float, Boolean> {
            val pos = one(wp, name); focus(pos); attrs(pos, setOf("relativeFrom"))
            val relative = pos.attribute("", "relativeFrom")
            requireThat(relative in if (horizontal) setOf("page", "margin", "column") else setOf("page", "margin", "paragraph"), "Unsupported line anchor")
            requireThat(pos.elements().size == 1 && pos.elements().single().name.localPart == "posOffset", "Aligned lines are not qualified")
            val value = pos.elements().single().children.filterIsInstance<XmlText>().joinToString("") { it.text }.toLongOrNull() ?: reject("Invalid line offset")
            val margin = if (relative == "margin") if (horizontal) page.margins.left else page.margins.top else 0f
            return value / 12700f + margin to (relative in setOf("page", "margin"))
        }
        val x = position("positionH", true); val y = position("positionV", false)
        val ln = one(a, "ln"); focus(ln); attrs(ln, setOf("w", "cap", "cmpd", "algn"))
        requireThat(ln.attribute("", "cap") in setOf(null, "flat") && ln.attribute("", "algn") in setOf(null, "ctr"), "Unsupported line cap or alignment")
        val rgb = one(a, "srgbClr"); focus(rgb); attrs(rgb, setOf("val")); requireThat(rgb.elements().isEmpty(), "Transparent or transformed line colors are not qualified")
        val dash = all.singleOrNull { it.name.localPart == "prstDash" }?.attribute("", "val") ?: "solid"
        val compound = ln.attribute("", "cmpd") ?: "sng"
        requireThat(compound in setOf("sng", "dbl") && (compound == "sng" || dash == "solid"), "Unsupported compound line")
        val style = if (compound == "dbl") PdfLineStyle.DOUBLE else when (dash) { "solid" -> PdfLineStyle.SOLID; "dash" -> PdfLineStyle.DASHED; "dot" -> PdfLineStyle.DOTTED; else -> reject("Unsupported line dash") }
        val weight = numeric(ln.attribute("", "w"), 9525f) / 12700
        val stroke = PdfLineStroke(if (compound == "dbl") weight / 3 else weight, color(rgb.attribute("", "val") ?: reject("Missing line color")), style,
            if (dash == "dash") 4 * weight else if (dash == "dot") weight else null, if (dash == "solid") null else 3 * weight)
        val rotation = transform.attribute("", "rot")?.let { it.toIntOrNull() ?: reject("Invalid line rotation") } ?: 0
        val angle = Math.toRadians(rotation / 60000.0)
        val dx = width / 2 * if (transform.attribute("", "flipH") in setOf("1", "true")) -1 else 1
        val dy = height / 2 * if (transform.attribute("", "flipV") in setOf("1", "true")) -1 else 1
        val rx = (dx * kotlin.math.cos(angle) - dy * kotlin.math.sin(angle)).toFloat()
        val ry = (dx * kotlin.math.sin(angle) + dy * kotlin.math.cos(angle)).toFloat()
        return PdfLineDrawing(x.first + width / 2 - rx, y.first + height / 2 - ry, x.first + width / 2 + rx, y.first + height / 2 + ry,
            stroke, x.second, y.second, anchor.attribute("", "behindDoc") in setOf("1", "true"))
    }

    private fun style(value: String?): PdfLineStyle = when (value) {
        "single" -> PdfLineStyle.SOLID; "double" -> PdfLineStyle.DOUBLE; "dashed" -> PdfLineStyle.DASHED; "dotted" -> PdfLineStyle.DOTTED
        else -> reject("Unsupported paragraph border style")
    }
    private fun color(value: String): PdfColor {
        val rgb = when (value) { "auto", "black" -> "000000"; "white" -> "FFFFFF"; else -> value.removePrefix("#") }
        requireThat(rgb.matches(Regex("[0-9A-Fa-f]{6}")), "Unsupported line color")
        return PdfColor(rgb.substring(0, 2).toInt(16), rgb.substring(2, 4).toInt(16), rgb.substring(4, 6).toInt(16))
    }
    private fun numeric(value: String?, default: Float? = null): Float = value?.toLongOrNull()?.takeIf { it >= 0 && it <= Int.MAX_VALUE }?.toFloat()
        ?: if (value == null && default != null) default else reject("Invalid line dimension")
    private fun length(value: String): Float {
        val match = Regex("(-?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+))(pt|in|cm|mm)").matchEntire(value.trim()) ?: reject("Unsupported line dimension unit")
        val factor = when (match.groupValues[2]) { "pt" -> 1f; "in" -> 72f; "cm" -> 72 / 2.54f; else -> 72 / 25.4f }
        return match.groupValues[1].toFloat() * factor
    }
    private fun attrs(node: XmlElement, names: Set<String>, ns: String = "") {
        focus(node); requireThat(node.attributes.keys.all { it.namespaceURI == ns && it.localPart in names }, "Unsupported line formatting attribute")
    }
    private fun requireThat(condition: Boolean, message: String) { if (!condition) reject(message) }
}
