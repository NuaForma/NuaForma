package dev.nuaforma.docx.pdf.ownengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText
import dev.nuaforma.pdf.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/** Trusted-fixture proof only. Kept outside production until the supported profile is qualified. */
internal class OwnEngineDocxReader {
    private val context = GenerationContext()
    private val xml = ConversionXml(context)
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private val relationships = "http://schemas.openxmlformats.org/package/2006/relationships"
    private val drawing = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private val wp = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    private val picture = "http://schemas.openxmlformats.org/drawingml/2006/picture"

    fun read(bytes: ByteArray): PdfDocument {
        val parts = unpack(bytes)
        fun part(name: String) = xml.read(parts[name] ?: reject("Missing required package part"))
        val rootRelations = part("_rels/.rels")
        requireRelationships(rootRelations)
        check(rootRelations.elements().size == 1 && rootRelations.elements().single().attribute("", "Type") == "$rel/officeDocument" &&
            rootRelations.elements().single().attribute("", "Target") == "word/document.xml", "Unsupported document relationship")
        val types = part("[Content_Types].xml")
        check(types.descendants().none { it.attributes.values.any { value -> value.contains("macroEnabled", true) } }, "Macros are unsupported")
        val document = part("word/document.xml")
        check(document.name.namespaceURI == word && document.name.localPart == "document", "Unsupported DOCX root")
        children(document, setOf("body"))
        val body = document.one("body") ?: reject("Missing document body")
        children(body, setOf("p", "tbl", "sectPr"))
        val section = body.one("sectPr") ?: reject("Explicit section geometry is required")
        check(body.elements().last() === section && body.elements().count { it.name.localPart == "sectPr" } == 1, "Multiple sections are unsupported")
        children(section, setOf("pgSz", "pgMar", "headerReference", "footerReference"))
        val size = section.one("pgSz") ?: reject("Missing page size")
        attributes(size, setOf("w", "h"))
        val margins = section.one("pgMar") ?: reject("Missing page margins")
        attributes(margins, setOf("top", "right", "bottom", "left", "header", "footer", "gutter"))
        check((margins.value("gutter") ?: "0") == "0", "Page gutters are unsupported")
        val page = PdfPage(twips(size.value("w")), twips(size.value("h")),
            PdfMargins(twips(margins.value("top")), twips(margins.value("right")), twips(margins.value("bottom")), twips(margins.value("left"))))
        val relationRoot = part("word/_rels/document.xml.rels")
        requireRelationships(relationRoot)
        fun readRelations(root: XmlElement): Map<String, String> {
        requireRelationships(root)
        val result = root.elements().associate { element ->
            val id = element.attribute("", "Id") ?: reject("Missing relationship ID")
            val target = element.attribute("", "Target") ?: reject("Missing relationship target")
            check(target.matches(Regex("(?:media/[A-Za-z0-9_.-]+\\.png|styles\\.xml|header1\\.xml|footer1\\.xml)")), "Unsupported relationship target")
            val type = element.attribute("", "Type")
            check(type == "$rel/image" && target.startsWith("media/") || type == "$rel/styles" && target == "styles.xml" ||
                type == "$rel/header" && target == "header1.xml" || type == "$rel/footer" && target == "footer1.xml", "Unsupported relationship type")
            id to target
        }
        check(result.size == root.elements().size, "Duplicate relationship ID")
        return result
        }
        val relations = readRelations(relationRoot)
        val stylesRoot = part("word/styles.xml")
        check(stylesRoot.name.namespaceURI == word && stylesRoot.name.localPart == "styles", "Unsupported styles root")
        children(stylesRoot, setOf("docDefaults", "style"))
        val defaults = stylesRoot.one("docDefaults")
        defaults?.let { children(it, setOf("rPrDefault", "pPrDefault")) }
        defaults?.one("rPrDefault")?.let { children(it, setOf("rPr")) }
        defaults?.one("pPrDefault")?.let { children(it, setOf("pPr")) }
        val defaultRun = defaults?.one("rPrDefault")?.one("rPr")
        val defaultParagraph = defaults?.one("pPrDefault")?.one("pPr")
        val styles = stylesRoot.elements().filter { it.name.localPart == "style" }.associateBy { it.value("styleId") ?: reject("Style without ID") }
        check(styles.size == stylesRoot.elements().count { it.name.localPart == "style" }, "Duplicate style ID")
        val normal = styles.values.singleOrNull { it.value("type") == "paragraph" && it.value("default") in setOf("1", "true") }?.value("styleId")
            ?: reject("Explicit default paragraph style is required")
        fun chain(id: String, type: String, seen: Set<String> = emptySet()): List<XmlElement> {
            check(id !in seen && seen.size < 16, "Cyclic or excessive style inheritance")
            val style = styles[id] ?: reject("Unknown style")
            check(style.value("type") == type, "Wrong style type")
            children(style, setOf("name", "basedOn", "pPr", "rPr"))
            val parent = style.one("basedOn")?.value("val")
            return (parent?.let { chain(it, type, seen + id) } ?: emptyList()) + style
        }
        fun readParagraph(paragraph: XmlElement, partRelations: Map<String, String>, fields: Boolean = false): PdfParagraph {
            context.checkActive()
            children(paragraph, if (fields) setOf("pPr", "r", "fldSimple") else setOf("pPr", "r"))
            val direct = paragraph.one("pPr")
            val styleChain = chain(direct?.one("pStyle")?.value("val") ?: normal, "paragraph")
            val paragraphChain = listOfNotNull(defaultParagraph) + styleChain.mapNotNull { it.one("pPr") } + listOfNotNull(direct)
            val geometry = paragraphChain.fold(Geometry()) { result, property -> paragraphProperties(property, result) }
            val base = styleChain.fold(runProperties(defaultRun, Face(), false)) { result, style -> runProperties(style.one("rPr"), result, true) }
            fun faceFor(properties: XmlElement?): Face {
                val characterStyle = properties?.one("rStyle")?.value("val")
                val inherited = characterStyle?.let { id ->
                    chain(id, "character").fold(base) { acc, style -> runProperties(style.one("rPr"), acc, true) }
                } ?: base
                return runProperties(properties, inherited, false)
            }
            val runs = paragraph.elements().filter { it.name.localPart != "pPr" }.map { run ->
                if (run.name.localPart == "fldSimple") {
                    attributes(run, setOf("instr"))
                    check(run.value("instr")?.trim() == "PAGE", "Only PAGE fields in page bands are supported")
                    children(run, setOf("r"))
                    val cached = run.elements().singleOrNull() ?: reject("Invalid PAGE field")
                    children(cached, setOf("rPr", "t"))
                    val face = faceFor(cached.one("rPr"))
                    return@map PdfPageNumber(PdfTextStyle(face.id(), face.size ?: reject("Missing font size"), face.color))
                }
                children(run, setOf("rPr", "t", "drawing"))
                val face = faceFor(run.one("rPr"))
                val contents = run.elements().filter { it.name.localPart != "rPr" }
                check(contents.size == 1, "Each proof run must contain one text or image node")
                val item = contents.single()
                when (item.name.localPart) {
                    "t" -> {
                        check(item.elements().isEmpty(), "Invalid text node")
                        val text = item.children.filterIsInstance<XmlText>().joinToString("") { it.text }
                        check(text == text.trim() || item.attribute("http://www.w3.org/XML/1998/namespace", "space") == "preserve", "Significant edge whitespace requires explicit preservation")
                        PdfTextRun(text.ifEmpty { " " }, PdfTextStyle(face.id(), face.size ?: reject("Missing font size"), face.color))
                    }
                    "drawing" -> PdfImageRun(image(item, partRelations, parts))
                    else -> reject("Unsupported run content")
                }
            }
            val content = runs.ifEmpty { listOf(PdfTextRun(" ", PdfTextStyle(base.id(), base.size ?: reject("Missing font size"), base.color))) }
            check(content.none { it is PdfImageRun } || content.size == 1, "Mixed image/text paragraphs are outside the proof profile")
            return PdfParagraph(content, geometry.before, geometry.after, PdfParagraphFormat(geometry.alignment, geometry.left, geometry.right,
                geometry.first, geometry.line ?: reject("Only explicit exact line spacing is supported"), true,
                geometry.keepWithNext, geometry.keepLines, geometry.widowControl, geometry.pageBreakBefore))
        }
        fun readTable(table: XmlElement, partRelations: Map<String, String>, fields: Boolean, depth: Int = 0): PdfTable {
            check(depth <= 1, "Only one nested table level is supported")
            children(table, setOf("tblPr", "tblGrid", "tr"))
            val properties = table.one("tblPr") ?: reject("Explicit table properties required")
            children(properties, setOf("tblW", "tblInd", "tblLayout", "tblBorders", "tblCellMar"))
            check(properties.one("tblLayout")?.value("type") == "fixed", "Only fixed table layout is supported")
            val grid = table.one("tblGrid") ?: reject("Missing table grid")
            check(grid.elements().all { it.name.namespaceURI == word && it.name.localPart == "gridCol" }, "Unsupported table grid")
            val widths = grid.elements().map { twips(it.value("w")) }
            check(widths.size in 1..16, "Invalid table grid")
            fun dimension(node: XmlElement): Float {
                attributes(node, setOf("w", "type"))
                check(node.value("type") == "dxa", "Only physical table dimensions are supported")
                return twips(node.value("w"))
            }
            val tableWidth = dimension(properties.one("tblW") ?: reject("Missing table width"))
            check(kotlin.math.abs(widths.sum() - tableWidth) < 0.01f, "Table width differs from grid")
            val cellMargins = properties.one("tblCellMar") ?: reject("Explicit cell margins required")
            children(cellMargins, setOf("top", "right", "bottom", "left"))
            fun edge(name: String) = dimension(cellMargins.one(name) ?: reject("Missing cell margin"))
            val padding = PdfMargins(edge("top"), edge("right"), edge("bottom"), edge("left"))
            val borderNodes = properties.one("tblBorders") ?: reject("Explicit table borders required")
            children(borderNodes, setOf("top", "left", "bottom", "right", "insideH", "insideV"))
            val borders = borderNodes.elements().map {
                attributes(it, setOf("val", "sz", "color"))
                check(it.value("val") == "single", "Only solid table borders are supported")
                PdfBorder(number(it.value("sz")) / 8, color(it.value("color")))
            }
            check(borders.isEmpty() || borders.size == 6 && borders.distinct().size == 1, "Only uniform table borders are supported")
            val rowNodes = table.elements().filter { it.name.localPart == "tr" }
            val headerFlags = mutableListOf<Boolean>()
            data class CellSpec(val blocks: List<PdfBlock>, val background: PdfColor?, val span: Int, var rows: Int = 1)
            val specs = mutableListOf<Pair<List<CellSpec>, Boolean>>()
            var previous = mapOf<Int, CellSpec>()
            for (row in rowNodes) {
                children(row, setOf("trPr", "tc"))
                val rowProperties = row.one("trPr")
                rowProperties?.let { children(it, setOf("cantSplit", "tblHeader")) }
                fun enabled(node: XmlElement): Boolean = when (node.value("val")) { null, "1", "true" -> true; "0", "false" -> false; else -> reject("Invalid row toggle") }
                val allowSplit = rowProperties?.one("cantSplit")?.let { !enabled(it) } ?: true
                headerFlags += rowProperties?.one("tblHeader")?.let(::enabled) == true
                val cells = mutableListOf<CellSpec>()
                val active = mutableMapOf<Int, CellSpec>()
                var column = 0
                for (cell in row.elements().filter { it.name.localPart == "tc" }) {
                    children(cell, setOf("tcPr", "p", "tbl"))
                    val cp = cell.one("tcPr") ?: reject("Explicit cell properties required")
                    children(cp, setOf("tcW", "shd", "gridSpan", "vMerge"))
                    val span = cp.one("gridSpan")?.let { attributes(it, setOf("val")); number(it.value("val")).toInt() } ?: 1
                    check(span in 1..16 && column + span <= widths.size, "Invalid grid span")
                    val actualWidth = dimension(cp.one("tcW") ?: reject("Missing cell width"))
                    check(kotlin.math.abs(actualWidth - widths.subList(column, column + span).sum()) < 0.01f, "Inconsistent cell width")
                    val background = cp.one("shd")?.let {
                        attributes(it, setOf("val", "color", "fill"))
                        check(it.value("val") == "clear" && it.value("color") == "auto", "Unsupported cell shading")
                        color(it.value("fill"))
                    }
                    val blocks = cell.elements().filter { it.name.localPart != "tcPr" }.map { child ->
                        when (child.name.localPart) { "p" -> readParagraph(child, partRelations, fields); "tbl" -> readTable(child, partRelations, fields, depth + 1); else -> reject("Unsupported cell block") }
                    }.let { content ->
                        // Word's required empty cell-end paragraph after a nested table is a terminator,
                        // rather than another visible exact-height text line in this trial profile.
                        val last = content.lastOrNull()
                        if (content.size >= 2 && content[content.lastIndex - 1] is PdfTable && last is PdfParagraph &&
                            last.runs.all { it is PdfTextRun && it.text.isBlank() }) content.dropLast(1) else content
                    }
                    val merge = cp.one("vMerge")?.let { attributes(it, setOf("val")); it.value("val") ?: "continue" }
                    when (merge) {
                        "continue" -> {
                            val origin = previous[column] ?: reject("Vertical continuation has no origin")
                            check(origin.span == span && origin.background == background, "Inconsistent vertical continuation")
                            check(blocks.all { it is PdfParagraph && it.runs.all { run -> run is PdfTextRun && run.text.isBlank() } }, "Continuation cells must not contain additional content")
                            origin.rows++
                            active[column] = origin
                        }
                        null, "restart" -> {
                            val spec = CellSpec(blocks, background, span)
                            cells += spec
                            if (merge == "restart") active[column] = spec
                        }
                        else -> reject("Invalid vertical merge")
                    }
                    column += span
                }
                check(column == widths.size, "Incomplete table row")
                specs += cells to allowSplit
                previous = active
            }
            val rows = specs.map { (cells, allow) -> PdfTableRow(cells.map { PdfTableCell(it.blocks, it.background, it.span, it.rows) }, allow, splitOversized = true) }
            val count = headerFlags.takeWhile { it }.size
            check(headerFlags.drop(count).none { it }, "Table headers must be leading rows")
            val indent = properties.one("tblInd")?.let(::dimension) ?: reject("Explicit table indent required")
            val border = borders.firstOrNull()
            val physicalIndent = if (depth == 0) indent - padding.left else indent + (border?.width ?: 0f) / 2
            return PdfTable(widths, rows, padding, border, count, physicalIndent)
        }
        fun readBox(pict: XmlElement, partRelations: Map<String, String>): PdfTextBox {
            val vml = "urn:schemas-microsoft-com:vml"
            val word10 = "urn:schemas-microsoft-com:office:word"
            val shape = pict.elements().singleOrNull() ?: reject("Only one shape per drawing is supported")
            check(shape.name.namespaceURI == vml && shape.name.localPart == "rect", "Only rectangular VML text frames are supported")
            check(shape.attributes.keys.all { it.namespaceURI.isEmpty() && it.localPart in setOf("id", "style", "filled", "fillcolor", "stroked", "strokecolor", "strokeweight") }, "Unsupported frame attribute")
            val declarations = (shape.attribute("", "style") ?: reject("Frame style required")).split(';').filter { it.isNotBlank() }.map {
                val pair = it.split(':', limit = 2); check(pair.size == 2, "Invalid frame style"); pair[0].trim() to pair[1].trim()
            }
            val css = declarations.toMap()
            check(css.size == declarations.size && css.keys.all { it in setOf("position", "margin-left", "margin-top", "width", "height", "rotation", "mso-position-horizontal-relative", "mso-position-vertical-relative", "v-text-anchor") }, "Unsupported or duplicate frame style")
            check(css["position"] == "absolute", "Only positioned frames are supported")
            fun points(value: String?): Float = value?.takeIf { it.endsWith("pt") }?.dropLast(2)?.toFloatOrNull()?.takeIf { it.isFinite() } ?: reject("Physical frame dimensions required")
            val horizontal = css["mso-position-horizontal-relative"]
            check(horizontal in setOf("page", "margin", "text"), "Unsupported horizontal anchor")
            val vertical = css["mso-position-vertical-relative"]
            check(vertical in setOf("page", "margin", "text"), "Unsupported vertical anchor")
            val box = shape.elements().singleOrNull { it.name.namespaceURI == vml && it.name.localPart == "textbox" } ?: reject("Missing textbox")
            check(box.attributes.keys.all { it.namespaceURI.isEmpty() && it.localPart == "inset" }, "Unsupported textbox fitting")
            val inset = (box.attribute("", "inset") ?: reject("Explicit frame padding required")).split(',').map(::points)
            check(inset.size == 4, "Invalid frame padding")
            val content = box.elements().singleOrNull() ?: reject("Missing text frame content")
            check(content.name.namespaceURI == word && content.name.localPart == "txbxContent", "Invalid text frame content")
            children(content, setOf("p"))
            val wrapNode = shape.elements().singleOrNull { it.name.namespaceURI == word10 && it.name.localPart == "wrap" }
            check(shape.elements().all { it === box || it === wrapNode }, "Unsupported shape effect")
            check(wrapNode == null || wrapNode.attribute("", "type") == "topAndBottom" && wrapNode.attributes.size == 1, "Unsupported frame wrapping")
            val angle = css["rotation"]?.toFloatOrNull() ?: if ("rotation" in css) reject("Invalid rotation") else 0f
            check(angle == 0f, "VML rotation/text-flow combinations are not qualified; use a DrawingML frame")
            return PdfTextBox(points(css["margin-left"]) + if (horizontal == "page") 0f else page.margins.left,
                points(css["margin-top"]) + if (vertical == "margin") page.margins.top else 0f,
                points(css["width"]), points(css["height"]), content.elements().map { readParagraph(it, partRelations) },
                if (vertical == "text") PdfBoxAnchor.PARAGRAPH else PdfBoxAnchor.PAGE, angle,
                PdfMargins(inset[1], inset[2], inset[3], inset[0]),
                if (shape.attribute("", "filled") == "f") null else color(shape.attribute("", "fillcolor")?.removePrefix("#") ?: reject("Explicit frame fill required")),
                if (shape.attribute("", "stroked") == "f") null else PdfBorder(points(shape.attribute("", "strokeweight")), color(shape.attribute("", "strokecolor")?.removePrefix("#") ?: reject("Explicit frame border required"))),
                when (css["v-text-anchor"]) { "top" -> PdfVerticalAlignment.TOP; "middle" -> PdfVerticalAlignment.CENTER; "bottom" -> PdfVerticalAlignment.BOTTOM; else -> reject("Unsupported frame vertical alignment") },
                if (wrapNode == null) PdfBoxWrap.NONE else PdfBoxWrap.TOP_BOTTOM)
        }
        fun readDrawingBox(node: XmlElement, partRelations: Map<String, String>): PdfTextBox {
            val wps = "http://schemas.microsoft.com/office/word/2010/wordprocessingShape"
            val anchor = node.elements().singleOrNull() ?: reject("Invalid floating drawing")
            check(anchor.name.namespaceURI == wp && anchor.name.localPart == "anchor", "Floating anchor required")
            val expected = mapOf("distT" to "0", "distB" to "0", "distL" to "0", "distR" to "0", "simplePos" to "0", "relativeHeight" to "0", "behindDoc" to "0", "locked" to "0", "layoutInCell" to "1", "allowOverlap" to "1")
            check(anchor.attributes.size == expected.size && anchor.attributes.all { it.key.namespaceURI.isEmpty() && expected[it.key.localPart] == it.value }, "Unsupported floating anchor policy")
            fun one(parent: XmlElement, ns: String, name: String) = parent.elements().singleOrNull { it.name.namespaceURI == ns && it.name.localPart == name } ?: reject("Missing or duplicate drawing property")
            fun numeric(value: String?) = value?.toFloatOrNull()?.takeIf { it.isFinite() } ?: reject("Invalid drawing coordinate")
            fun offset(name: String): Float {
                val position = one(anchor, wp, name)
                check(position.attribute("", "relativeFrom") == "page" && position.elements().size == 1, "Only page-relative DrawingML frames are qualified")
                val raw = one(position, wp, "posOffset").children.filterIsInstance<XmlText>().joinToString("") { it.text }
                return numeric(raw) / 12700
            }
            val extent = one(anchor, wp, "extent")
            val simple = one(anchor, wp, "simplePos")
            check(simple.attribute("", "x") == "0" && simple.attribute("", "y") == "0", "Unsupported simple position")
            check(anchor.elements().all { it.name.namespaceURI == wp && it.name.localPart in setOf("simplePos", "positionH", "positionV", "extent", "effectExtent", "wrapNone", "docPr", "cNvGraphicFramePr") || it.name.namespaceURI == drawing && it.name.localPart == "graphic" }, "Unsupported floating shape")
            one(anchor, wp, "wrapNone")
            check(one(anchor, wp, "effectExtent").attributes.values.all { it == "0" }, "Drawing effects are unsupported")
            val graphic = one(one(anchor, drawing, "graphic"), drawing, "graphicData")
            check(graphic.attribute("", "uri") == wps && graphic.elements().size == 1, "Unsupported floating graphic")
            val shape = one(graphic, wps, "wsp")
            check(shape.elements().size == 4 && shape.elements().all { it.name.namespaceURI == wps && it.name.localPart in setOf("cNvSpPr", "spPr", "txbx", "bodyPr") }, "Unsupported shape content")
            check(one(shape, wps, "cNvSpPr").attribute("", "txBox") == "1", "Text frame required")
            val props = one(shape, wps, "spPr")
            check(props.elements().all { it.name.namespaceURI == drawing && it.name.localPart in setOf("xfrm", "prstGeom", "solidFill", "noFill", "ln") }, "Unsupported shape effects")
            val transform = one(props, drawing, "xfrm")
            check(transform.attributes.keys.all { it.namespaceURI.isEmpty() && it.localPart == "rot" }, "Flipped frames are unsupported")
            val origin = one(transform, drawing, "off")
            check(origin.attribute("", "x") == "0" && origin.attribute("", "y") == "0", "Unsupported local transform")
            val size = one(transform, drawing, "ext")
            check(size.attributes == extent.attributes && one(props, drawing, "prstGeom").attribute("", "prst") == "rect", "Inconsistent frame geometry")
            fun fill(parent: XmlElement): PdfColor? {
                val values = parent.elements().filter { it.name.namespaceURI == drawing && it.name.localPart in setOf("solidFill", "noFill") }
                check(values.size == 1, "Explicit single frame fill required")
                return if (values.single().name.localPart == "noFill") null else color(one(values.single(), drawing, "srgbClr").attribute("", "val"))
            }
            val bodyProps = one(shape, wps, "bodyPr")
            check(bodyProps.attributes.keys.all { it.namespaceURI.isEmpty() && it.localPart in setOf("rot", "vert", "wrap", "lIns", "tIns", "rIns", "bIns", "anchor") }, "Unsupported text frame policy")
            check(bodyProps.attribute("", "rot") == "0" && bodyProps.attribute("", "vert") == "horz" && bodyProps.attribute("", "wrap") == "square", "Unsupported text direction")
            check(bodyProps.elements().size == 1 && one(bodyProps, drawing, "noAutofit").attributes.isEmpty(), "Frame autofitting is unsupported")
            val content = one(one(shape, wps, "txbx"), word, "txbxContent")
            children(content, setOf("p"))
            val line = one(props, drawing, "ln")
            val lineColor = fill(line)
            return PdfTextBox(offset("positionH"), offset("positionV"), numeric(extent.attribute("", "cx")) / 12700,
                numeric(extent.attribute("", "cy")) / 12700, content.elements().map { readParagraph(it, partRelations) }, PdfBoxAnchor.PAGE,
                (transform.attribute("", "rot")?.let(::numeric) ?: 0f) / 60000,
                PdfMargins(numeric(bodyProps.attribute("", "tIns")) / 12700, numeric(bodyProps.attribute("", "rIns")) / 12700,
                    numeric(bodyProps.attribute("", "bIns")) / 12700, numeric(bodyProps.attribute("", "lIns")) / 12700),
                fill(props), lineColor?.let { PdfBorder(numeric(line.attribute("", "w")) / 12700, it) },
                when (bodyProps.attribute("", "anchor")) { "t" -> PdfVerticalAlignment.TOP; "ctr" -> PdfVerticalAlignment.CENTER; "b" -> PdfVerticalAlignment.BOTTOM; else -> reject("Unsupported vertical alignment") })
        }
        fun readBlocks(root: XmlElement, partRelations: Map<String, String>, fields: Boolean): List<PdfBlock> = root.elements().filter { it.name.localPart != "sectPr" }.flatMap { block ->
            when (block.name.localPart) {
                "p" -> {
                    val frames = block.elements().filter { it.name.localPart == "r" }.flatMap { it.elements().filter { node -> node.name.namespaceURI == word && (node.name.localPart == "pict" || node.name.localPart == "drawing" && node.elements().any { it.name.namespaceURI == wp && it.name.localPart == "anchor" }) } }
                    check(frames.isEmpty() || !fields, "Frames in page bands are outside this profile")
                    val plain = XmlElement(block.name, block.attributes, block.namespaces)
                    plain.children += block.children.mapNotNull { child ->
                        if (child is XmlElement && child.name.localPart == "r" && child.elements().any { it in frames }) {
                            check(child.elements().all { it in frames || it.name.localPart == "rPr" }, "Mixed frame/text runs are unsupported"); null
                        } else child
                    }
                    frames.map { if (it.name.localPart == "pict") readBox(it, partRelations) else readDrawingBox(it, partRelations) } + readParagraph(plain, partRelations, fields)
                }
                "tbl" -> listOf(readTable(block, partRelations, fields))
                else -> reject("Unsupported block")
            }
        }
        fun band(name: String): PdfPageBand? {
            val reference = section.one("${name}Reference") ?: return null
            check(reference.value("type") == "default", "Only shared default page bands are supported")
            val target = relations[reference.attribute(rel, "id")] ?: reject("Missing page-band relationship")
            check(target == "${name}1.xml", "Wrong page-band relationship")
            val bandRoot = part("word/$target")
            check(bandRoot.name.namespaceURI == word && bandRoot.name.localPart == if (name == "header") "hdr" else "ftr", "Invalid page band")
            children(bandRoot, setOf("p", "tbl"))
            val ownRelations = parts["word/_rels/$target.rels"]?.let { readRelations(xml.read(it)) } ?: emptyMap()
            val offset = twips(margins.value(name))
            val extent = (if (name == "header") page.margins.top else page.margins.bottom) - offset
            return PdfPageBand(offset, extent, readBlocks(bandRoot, ownRelations, true))
        }
        val blocks = readBlocks(body, relations, false)
        val header = band("header")
        val footer = band("footer")
        val flow = if (header != null || footer != null || blocks.any { it is PdfTable || it is PdfTextBox || it is PdfParagraph &&
            (it.format.keepWithNext || it.format.keepLines || it.format.widowControl || it.format.pageBreakBefore) }) PdfFlow(header, footer) else null
        return PdfDocument(page, blocks, flow)
    }

    private data class Face(val family: String? = null, val size: Float? = null, val bold: Boolean = false,
        val italic: Boolean = false, val color: PdfColor = PdfColor(0, 0, 0)) {
        fun id(): String {
            check(family == "Liberation Sans") { "Proof font family is not supplied" }
            return when { bold && italic -> "bolditalic"; bold -> "bold"; italic -> "italic"; else -> "regular" }
        }
    }

    private fun runProperties(property: XmlElement?, initial: Face, toggle: Boolean): Face {
        if (property == null) return initial
        children(property, setOf("rStyle", "rFonts", "sz", "b", "i", "color"))
        var result = initial
        property.one("rFonts")?.let {
            attributes(it, setOf("ascii", "hAnsi"))
            val family = it.value("ascii") ?: reject("Explicit Latin font required")
            check(it.value("hAnsi") == family, "Mixed font families are unsupported")
            result = result.copy(family = family)
        }
        property.one("sz")?.let { result = result.copy(size = number(it.value("val")) / 2) }
        fun enabled(node: XmlElement): Boolean = when (node.value("val")) { null, "true", "1", "on" -> true; "false", "0", "off" -> false; else -> reject("Invalid toggle") }
        property.one("b")?.let { result = result.copy(bold = if (toggle) initial.bold xor enabled(it) else enabled(it)) }
        property.one("i")?.let { result = result.copy(italic = if (toggle) initial.italic xor enabled(it) else enabled(it)) }
        property.one("color")?.let {
            attributes(it, setOf("val"))
            val value = it.value("val") ?: reject("Missing color")
            check(value.matches(Regex("[0-9A-Fa-f]{6}")), "Only explicit RGB colors are supported")
            result = result.copy(color = PdfColor(value.substring(0, 2).toInt(16), value.substring(2, 4).toInt(16), value.substring(4, 6).toInt(16)))
        }
        return result
    }

    private data class Geometry(val before: Float = 0f, val after: Float = 0f, val line: Float? = null,
        val alignment: PdfAlignment = PdfAlignment.LEFT, val left: Float = 0f, val right: Float = 0f, val first: Float = 0f,
        val keepWithNext: Boolean = false, val keepLines: Boolean = false, val widowControl: Boolean = true, val pageBreakBefore: Boolean = false)

    private fun paragraphProperties(property: XmlElement, initial: Geometry): Geometry {
        children(property, setOf("pStyle", "spacing", "jc", "ind", "widowControl", "keepNext", "keepLines", "pageBreakBefore"))
        var result = initial
        fun flag(node: XmlElement): Boolean {
            attributes(node, setOf("val"))
            return when (node.value("val")) { null, "true", "1", "on" -> true; "false", "0", "off" -> false; else -> reject("Invalid paragraph toggle") }
        }
        property.one("keepNext")?.let { result = result.copy(keepWithNext = flag(it)) }
        property.one("keepLines")?.let { result = result.copy(keepLines = flag(it)) }
        property.one("widowControl")?.let { result = result.copy(widowControl = flag(it)) }
        property.one("pageBreakBefore")?.let { result = result.copy(pageBreakBefore = flag(it)) }
        property.one("spacing")?.let {
            attributes(it, setOf("before", "after", "line", "lineRule"))
            if (it.value("line") != null || it.value("lineRule") != null) check(it.value("lineRule") == "exact" && it.value("line") != null, "Only exact line spacing is supported")
            result = result.copy(before = it.value("before")?.let(::twips) ?: result.before,
                after = it.value("after")?.let(::twips) ?: result.after, line = it.value("line")?.let(::twips) ?: result.line)
        }
        property.one("jc")?.let { result = result.copy(alignment = when (it.value("val")) {
            "left" -> PdfAlignment.LEFT; "center" -> PdfAlignment.CENTER; "right" -> PdfAlignment.RIGHT; else -> reject("Unsupported alignment")
        }) }
        property.one("ind")?.let {
            attributes(it, setOf("left", "right", "firstLine", "hanging"))
            check(it.value("firstLine") == null || it.value("hanging") == null, "Conflicting indentation")
            result = result.copy(left = it.value("left")?.let(::twips) ?: result.left, right = it.value("right")?.let(::twips) ?: result.right,
                first = it.value("firstLine")?.let(::twips) ?: it.value("hanging")?.let { value -> -twips(value) } ?: result.first)
        }
        return result
    }

    private fun image(node: XmlElement, relations: Map<String, String>, parts: Map<String, ByteArray>): PdfImage {
        check(node.elements().size == 1, "Unsupported drawing")
        val inline = node.elements().single()
        check(inline.name.namespaceURI == wp && inline.name.localPart == "inline", "Only inline images are supported")
        check(inline.attributes.all { it.key.namespaceURI.isEmpty() && it.key.localPart in setOf("distT", "distB", "distL", "distR") && it.value == "0" }, "Image wrapping is unsupported")
        val allowed = mapOf(wp to setOf("inline", "extent", "docPr", "cNvGraphicFramePr"), drawing to setOf("graphic", "graphicData", "blip", "stretch", "fillRect", "xfrm", "off", "ext", "prstGeom", "avLst"),
            picture to setOf("pic", "nvPicPr", "cNvPr", "cNvPicPr", "blipFill", "spPr"))
        check(inline.descendants().all { it.name.localPart in (allowed[it.name.namespaceURI] ?: emptySet()) }, "Unsupported drawing effect or geometry")
        val extent = inline.elements().singleOrNull { it.name.namespaceURI == wp && it.name.localPart == "extent" } ?: reject("Missing image extent")
        val blip = inline.descendants().singleOrNull { it.name.namespaceURI == drawing && it.name.localPart == "blip" } ?: reject("Missing image relationship")
        check(blip.attributes.keys.all { it.namespaceURI == rel && it.localPart == "embed" }, "Unsupported or external image reference")
        val target = relations[blip.attribute(rel, "embed")] ?: reject("Missing embedded image")
        check(target.startsWith("media/"), "Invalid image relationship")
        val transform = inline.descendants().singleOrNull { it.name.namespaceURI == drawing && it.name.localPart == "xfrm" } ?: reject("Missing image transform")
        check(transform.attributes.isEmpty(), "Rotated or flipped images are unsupported")
        val offset = transform.elements().singleOrNull { it.name.localPart == "off" } ?: reject("Missing image offset")
        check(offset.attribute("", "x") == "0" && offset.attribute("", "y") == "0", "Unsupported image offset")
        val transformedExtent = transform.elements().singleOrNull { it.name.localPart == "ext" } ?: reject("Missing picture extent")
        check(transformedExtent.attribute("", "cx") == extent.attribute("", "cx") && transformedExtent.attribute("", "cy") == extent.attribute("", "cy"), "Nonmatching picture transforms are unsupported")
        val shape = inline.descendants().singleOrNull { it.name.localPart == "prstGeom" } ?: reject("Missing image geometry")
        check(shape.attribute("", "prst") == "rect", "Unsupported image geometry")
        return PdfImage(parts["word/$target"] ?: reject("Missing image bytes"), number(extent.attribute("", "cx")) / 12700,
            number(extent.attribute("", "cy")) / 12700)
    }

    private fun unpack(bytes: ByteArray): Map<String, ByteArray> {
        check(bytes.size <= 8_388_608, "DOCX byte limit exceeded")
        val parts = linkedMapOf<String, ByteArray>()
        var total = 0
        try {
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    context.checkActive()
                    val entry = zip.nextEntry ?: break
                    check(parts.size < 64 && entry.name !in parts && !entry.isDirectory, "Invalid or excessive ZIP entries")
                    check(entry.name.matches(Regex("(?:\\[Content_Types]\\.xml|_rels/\\.rels|word/(?:(?:document|styles|header1|footer1)\\.xml|_rels/(?:document|header1|footer1)\\.xml\\.rels|media/[A-Za-z0-9_.-]+\\.png))")), "Unsupported package part")
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        context.checkActive()
                        val read = zip.read(buffer)
                        if (read < 0) break
                        total += read
                        check(total <= 8_388_608 && output.size() + read <= 4_194_304, "Expanded DOCX limit exceeded")
                        output.write(buffer, 0, read)
                    }
                    parts[entry.name] = output.toByteArray()
                    zip.closeEntry()
                }
            }
        } catch (_: IOException) { reject("Malformed DOCX ZIP") }
        return parts
    }

    private fun requireRelationships(root: XmlElement) {
        check(root.name.namespaceURI == relationships && root.name.localPart == "Relationships", "Invalid relationships")
        check(root.elements().all { it.name.namespaceURI == relationships && it.name.localPart == "Relationship" &&
            it.attribute("", "TargetMode") == null }, "External or invalid relationships are unsupported")
    }
    private fun children(node: XmlElement, allowed: Set<String>) {
        check(node.elements().all { it.name.namespaceURI == word && it.name.localPart in allowed }, "Unsupported document feature")
        check(node.elements().groupBy { it.name.localPart }.all { (name, values) -> name in setOf("r", "p", "style", "tbl", "tr", "tc", "fldSimple") || values.size == 1 }, "Duplicate property")
    }
    private fun attributes(node: XmlElement, allowed: Set<String>) {
        check(node.attributes.keys.all { it.namespaceURI == word && it.localPart in allowed }, "Unsupported formatting attribute")
    }
    private fun XmlElement.one(name: String): XmlElement? = elements().singleOrNull { it.name.namespaceURI == word && it.name.localPart == name }
    private fun XmlElement.value(name: String) = attribute(word, name)
    private fun number(value: String?): Float = value?.toLongOrNull()?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toFloat() ?: reject("Invalid numeric property")
    private fun twips(value: String?): Float = number(value) / 20
    private fun color(value: String?): PdfColor {
        check(value != null && value.matches(Regex("[0-9A-Fa-f]{6}")), "Explicit RGB color required")
        return PdfColor(value!!.substring(0, 2).toInt(16), value.substring(2, 4).toInt(16), value.substring(4, 6).toInt(16))
    }
    private fun check(condition: Boolean, message: String) { if (!condition) reject(message) }
    private fun reject(message: String): Nothing = throw IllegalArgumentException(message)
}
