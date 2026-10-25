package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText
import dev.nuaforma.pdf.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/** Bounded Transitional OOXML profile. Unsupported visible constructs fail before rendering. */
internal class NativeDocxReader(
    private val context: GenerationContext,
    private val fontId: (String, Boolean, Boolean) -> String,
) {
    private val xml = ConversionXml(context)
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private val relationships = "http://schemas.openxmlformats.org/package/2006/relationships"
    private val drawing = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private val wp = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    private val picture = "http://schemas.openxmlformats.org/drawingml/2006/picture"

    private val provenance = java.util.IdentityHashMap<XmlElement, String>()
    private var activePart = "package"
    private val parsed = linkedMapOf<String, XmlElement>()
    private fun parse(bytes: ByteArray, part: String): XmlElement {
        activePart = part
        return parsed.getOrPut(part) { xml.read(bytes).also { root -> root.descendants().forEach { provenance[it] = part } } }
    }

    fun read(bytes: ByteArray): PdfDocument {
        val parts = unpack(bytes)
        parts.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.forEach { (name, content) -> parse(content, name) }
        val complexText = WordAuthoringMetadata.containsComplexText(parsed.values)
        parsed.forEach { (name, root) -> WordAuthoringMetadata.normalize(root, name, complexText) }
        for (name in listOf("word/fontTable.xml", "word/webSettings.xml")) parsed[name]?.let { root ->
            val expected = if (name.endsWith("fontTable.xml")) "fonts" else "webSettings"
            check(root.name.namespaceURI == word && root.name.localPart == expected, "Invalid font table or web settings")
            val allowed = if (expected == "fonts") setOf("fonts", "font", "panose1", "charset", "family", "pitch", "sig", "altName", "notTrueType") else setOf("webSettings")
            check(root.descendants().all { it.name.namespaceURI == word && it.name.localPart in allowed }, "Embedded font tables or web settings are outside this profile")
        }
        fun part(name: String) = parse(parts[name] ?: reject("Missing required package part"), name)
        val rootRelations = part("_rels/.rels")
        requireRelationships(rootRelations)
        val office = rootRelations.elements().filter { it.attribute("", "Type") == "$rel/officeDocument" }
        check(office.size == 1 && office.single().attribute("", "Target") == "word/document.xml", "Unsupported document relationship")
        check(rootRelations.elements().all { it === office.single() ||
            it.attribute("", "Type") == "$rel/extended-properties" && it.attribute("", "Target") == "docProps/app.xml" ||
            it.attribute("", "Type") == "http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" && it.attribute("", "Target") == "docProps/core.xml" }, "Unsupported package metadata relationship")
        val types = part("[Content_Types].xml")
        check(types.descendants().none { it.attributes.values.any { value -> value.contains("macroEnabled", true) } }, "Macros are unsupported")
        val document = part("word/document.xml")
        check(document.name.namespaceURI == word && document.name.localPart == "document", "Unsupported DOCX root")
        children(document, setOf("body"))
        val body = document.one("body") ?: reject("Missing document body")
        children(body, setOf("p", "tbl", "sectPr"))
        val finalSection = body.one("sectPr") ?: reject("Explicit section geometry is required")
        check(body.elements().last() === finalSection, "Final section properties must end the body")
        val ranges = mutableListOf<Pair<XmlElement, List<XmlElement>>>()
        val sectionEnds = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<XmlElement, Boolean>())
        var pending = mutableListOf<XmlElement>()
        for (block in body.elements().dropLast(1)) {
            pending += block
            if (block.name.localPart == "p") block.one("pPr")?.one("sectPr")?.let { properties ->
                check(block.one("pPr")!!.elements().count { it.name.localPart == "sectPr" } == 1, "Duplicate section properties")
                sectionEnds += block
                ranges += properties to pending.toList()
                pending = mutableListOf()
            }
        }
        check(pending.isNotEmpty(), "Empty final sections are outside this profile")
        ranges += finalSection to pending.toList()
        check(ranges.size <= 32, "Section budget exceeded")
        fun readGeometry(section: XmlElement): Pair<PdfPage, XmlElement> {
            attributes(section, emptySet())
            children(section, setOf("pgSz", "pgMar", "headerReference", "footerReference", "type", "titlePg", "pgNumType"))
            pageNumbering(section)
            section.one("titlePg")?.let(::pageToggle)
            for (kind in listOf("headerReference", "footerReference")) {
                val types = section.elements().filter { it.name.localPart == kind }.map { it.value("type") }
                check(types.all { it in setOf("default", "first", "even") } && types.toSet().size == types.size, "Invalid or duplicate page-band type")
            }
            section.one("type")?.let { type ->
                attributes(type, setOf("val")); children(type, emptySet())
                check(type.value("val") == "nextPage", "Only next-page section transitions are qualified")
            }
            val size = section.one("pgSz") ?: reject("Missing page size")
            attributes(size, setOf("w", "h", "orient"))
            val pageWidth = twips(size.value("w"))
            val pageHeight = twips(size.value("h"))
            when (size.value("orient")) {
                null -> Unit
                "portrait" -> check(pageWidth <= pageHeight, "Page orientation conflicts with physical dimensions")
                "landscape" -> check(pageWidth >= pageHeight, "Page orientation conflicts with physical dimensions")
                else -> reject("Unsupported page orientation")
            }
            val margins = section.one("pgMar") ?: reject("Missing page margins")
            attributes(margins, setOf("top", "right", "bottom", "left", "header", "footer", "gutter"))
            check((margins.value("gutter") ?: "0") == "0", "Page gutters are unsupported")
            val resolved = PdfPage(pageWidth, pageHeight,
                PdfMargins(twips(margins.value("top")), twips(margins.value("right")), twips(margins.value("bottom")), twips(margins.value("left"))))
            return resolved to margins
        }
        var (page, margins) = readGeometry(ranges.first().first)
        val relationRoot = part("word/_rels/document.xml.rels")
        requireRelationships(relationRoot)
        fun readRelations(root: XmlElement): Map<String, String> {
        requireRelationships(root)
        val result = root.elements().associate { element ->
            val id = element.attribute("", "Id") ?: reject("Missing relationship ID")
            val target = element.attribute("", "Target") ?: reject("Missing relationship target")
            check(target.matches(Regex("(?:media/[A-Za-z0-9_.-]+\\.png|styles\\.xml|numbering\\.xml|settings\\.xml|fontTable\\.xml|webSettings\\.xml|theme/theme1\\.xml|(?:header|footer)[1-9][0-9]?\\.xml)")), "Unsupported relationship target")
            val type = element.attribute("", "Type")
            check(type == "$rel/image" && target.startsWith("media/") || type == "$rel/styles" && target == "styles.xml" || type == "$rel/numbering" && target == "numbering.xml" || type == "$rel/settings" && target == "settings.xml" ||
                type == "$rel/theme" && target == "theme/theme1.xml" || type == "$rel/fontTable" && target == "fontTable.xml" || type == "$rel/webSettings" && target == "webSettings.xml" || type == "$rel/header" && target.matches(Regex("header[1-9][0-9]?\\.xml")) || type == "$rel/footer" && target.matches(Regex("footer[1-9][0-9]?\\.xml")), "Unsupported relationship type")
            id to target
        }
        check(result.size == root.elements().size, "Duplicate relationship ID")
        return result
        }
        val relations = readRelations(relationRoot)
        check(("word/settings.xml" in parts) == relations.values.contains("settings.xml"), "Settings relationship/part mismatch")
        check(("word/theme/theme1.xml" in parts) == relations.values.contains("theme/theme1.xml"), "Theme relationship/part mismatch")
        val themeFonts = parts["word/theme/theme1.xml"]?.let { bytes ->
            val theme = parse(bytes, "word/theme/theme1.xml")
            check(theme.name.namespaceURI == drawing && theme.name.localPart == "theme", "Invalid theme root")
            val scheme = theme.elements().singleOrNull { it.name.localPart == "themeElements" }?.elements()?.singleOrNull { it.name.localPart == "fontScheme" }
                ?: reject("Missing theme font scheme")
            listOf("major", "minor").associate { kind ->
                val family = scheme.elements().singleOrNull { it.name.namespaceURI == drawing && it.name.localPart == kind + "Font" }
                    ?.elements()?.singleOrNull { it.name.namespaceURI == drawing && it.name.localPart == "latin" }?.attribute("", "typeface")
                    ?: reject("Missing Latin theme font")
                check(family.isNotBlank(), "Empty Latin theme font")
                kind to family
            }
        } ?: emptyMap()
        fun resolveRun(property: XmlElement?, initial: Face) = runProperties(property, initial, themeFonts)
        val neighbors = java.util.IdentityHashMap<XmlElement, Pair<XmlElement?, XmlElement?>>()
        fun registerNeighbors(root: XmlElement) {
            for (parent in root.descendants()) {
                val nodes = parent.elements()
                nodes.forEachIndexed { i, node -> if (node.name.namespaceURI == word && node.name.localPart == "p")
                    neighbors[node] = nodes.getOrNull(i - 1)?.takeIf { it.name.localPart == "p" } to nodes.getOrNull(i + 1)?.takeIf { it.name.localPart == "p" }
                }
            }
        }
        registerNeighbors(document)
        for (i in 0 until ranges.lastIndex) {
            val last = ranges[i].second.last()
            val first = ranges[i + 1].second.first()
            neighbors[last]?.let { neighbors[last] = it.first to null }
            neighbors[first]?.let { neighbors[first] = null to it.second }
        }
        var decimalSeparator = "."
        var evenAndOddHeaders = false
        val defaultTabInterval = parts["word/settings.xml"]?.let { bytes ->
            val settings = parse(bytes, "word/settings.xml")
            check(settings.name.namespaceURI == word && settings.name.localPart == "settings", "Invalid settings root")
            attributes(settings, emptySet()); children(settings, setOf("defaultTabStop", "decimalSymbol", "evenAndOddHeaders"))
            evenAndOddHeaders = settings.one("evenAndOddHeaders")?.let(::pageToggle) ?: false
            settings.one("decimalSymbol")?.let { node -> attributes(node, setOf("val")); decimalSeparator = node.value("val") ?: reject("Missing decimal symbol"); check(decimalSeparator in setOf(".", ","), "Unsupported decimal symbol") }
            settings.one("defaultTabStop")?.let { node -> attributes(node, setOf("val")); twips(node.value("val")) }
        } ?: 36f
        check(defaultTabInterval in 1f..14_400f, "Invalid default tab interval")
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
        check(defaultParagraph?.one("numPr") == null, "Numbering in document defaults is outside the proof")
        check(("word/numbering.xml" in parts) == relations.values.contains("numbering.xml"), "Numbering relationship/part mismatch")
        val numbering = NativeDocxNumbering(parts["word/numbering.xml"]?.let { parse(it, "word/numbering.xml") }, body) { paragraph ->
            chain(paragraph.one("pPr")?.one("pStyle")?.value("val") ?: normal, "paragraph")
        }
        fun readParagraph(paragraph: XmlElement, partRelations: Map<String, String>, fields: Boolean = false, tableStyles: List<XmlElement> = emptyList()): PdfParagraph {
            context.checkActive()
            children(paragraph, if (fields) setOf("pPr", "r", "fldSimple") else setOf("pPr", "r"))
            val direct = paragraph.one("pPr")
            val styleChain = chain(direct?.one("pStyle")?.value("val") ?: normal, "paragraph")
            val listEntry = numbering.entry(paragraph)
            val sourceProperties = styleChain.mapNotNull { it.one("pPr") } + listOfNotNull(direct)
            if (listEntry == null && sourceProperties.any { it.one("numPr") != null }) {
                check(sourceProperties.mapNotNull { it.one("numPr")?.one("numId")?.value("val") }.lastOrNull() == "0", "Numbering outside body paragraphs is not qualified")
            }
            val paragraphChain = listOfNotNull(defaultParagraph) + tableStyles.mapNotNull { it.one("pPr") } + listOfNotNull(listEntry?.level?.one("pPr")) + styleChain.mapNotNull { it.one("pPr") } + listOfNotNull(direct)
            var geometry = paragraphChain.fold(Geometry()) { result, property -> paragraphProperties(property, result) }
            if (geometry.contextual) {
                fun same(node: XmlElement?) = node != null && (node.one("pPr")?.one("pStyle")?.value("val") ?: normal) == (direct?.one("pStyle")?.value("val") ?: normal)
                val (previous, next) = neighbors[paragraph] ?: (null to null)
                geometry = geometry.copy(before = if (same(previous)) 0f else geometry.before, after = if (same(next)) 0f else geometry.after)
            }
            check(listEntry != null || geometry.listTab == null, "List tab without numbering")
            val base = (tableStyles + styleChain).fold(resolveRun(defaultRun, Face())) { result, style -> resolveRun(style.one("rPr"), result) }
            fun faceFor(properties: XmlElement?): Face {
                val characterStyle = properties?.one("rStyle")?.value("val")
                val inherited = characterStyle?.let { id ->
                    chain(id, "character").fold(base) { acc, style -> resolveRun(style.one("rPr"), acc) }
                } ?: base
                return resolveRun(properties, inherited)
            }
            val runs = paragraph.elements().filter { it.name.localPart != "pPr" }.flatMap { run ->
                if (run.name.localPart == "fldSimple") {
                    attributes(run, setOf("instr"))
                    val instruction = run.value("instr")?.trim()
                    check(fields && instruction in setOf("PAGE", "NUMPAGES", "SECTIONPAGES"), "Only decimal page fields in page bands are supported")
                    children(run, setOf("r"))
                    val cached = run.elements().singleOrNull() ?: reject("Invalid page field")
                    children(cached, setOf("rPr", "t"))
                    val face = faceFor(cached.one("rPr"))
                    check(face.rtl != true, "Forced complex-script page fields are not qualified")
                    check(face.copy(languageTag = base.languageTag, rtl = base.rtl) == base, "Page-field cache formatting must match its paragraph style")
                    val style = PdfTextStyle(face.id(fontId), face.size ?: reject("Missing font size"), face.color, face.languageTag)
                    return@flatMap listOf(when (instruction) { "PAGE" -> PdfPageNumber(style); "NUMPAGES" -> PdfPageCount(style); else -> PdfSequencePageCount(style) })
                }
                children(run, setOf("rPr", "t", "tab", "drawing"))
                val face = faceFor(run.one("rPr"))
                val contents = run.elements().filter { it.name.localPart != "rPr" }
                check(contents.isNotEmpty(), "Each proof run needs content")
                contents.map { item -> when (item.name.localPart) {
                    "t" -> {
                        check(item.elements().isEmpty(), "Invalid text node")
                        val text = item.children.filterIsInstance<XmlText>().joinToString("") { it.text }
                        val hebrew = text.codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HEBREW }
                        check(face.rtl != true || hebrew, "Only Hebrew text may force complex-script reading order in this proof")
                        if (hebrew || face.rtl == true) check(geometry.direction == PdfTextDirection.RIGHT_TO_LEFT && face.rtl == true &&
                            text.codePoints().noneMatch { Character.getDirectionality(it) == Character.DIRECTIONALITY_LEFT_TO_RIGHT }, "Only explicitly marked Hebrew RTL runs are qualified")
                        if (hebrew) {
                            val explicit = run.one("rPr") ?: reject("Explicit complex-script properties are required")
                            check(explicit.one("rFonts")?.value("cs") == face.family && explicit.one("szCs")?.value("val")?.let { number(it) / 2 } == face.size &&
                                explicit.one("bCs") != null && explicit.one("iCs") != null, "Hebrew proof runs must pin all matching complex-script face properties")
                        }
                        check(text == text.trim() || item.attribute("http://www.w3.org/XML/1998/namespace", "space") == "preserve", "Significant edge whitespace requires explicit preservation")
                        PdfTextRun(text.ifEmpty { " " }, PdfTextStyle(face.id(fontId), face.size ?: reject("Missing font size"), face.color, face.languageTag))
                    }
                    "tab" -> { attributes(item, emptySet()); check(item.elements().isEmpty(), "Invalid tab control"); PdfTab(PdfTextStyle(face.id(fontId), face.size ?: reject("Missing font size"), face.color, face.languageTag)) }
                    "drawing" -> PdfImageRun(image(item, partRelations, parts))
                    else -> reject("Unsupported run content")
                } }
            }
            val content = runs.ifEmpty { listOf(PdfTextRun(" ", PdfTextStyle(base.id(fontId), base.size ?: reject("Missing font size"), base.color, base.languageTag))) }
            check(content.none { it is PdfImageRun } || content.size == 1, "Mixed image/text paragraphs are outside the proof profile")
            val label = listEntry?.let { entry ->
                check(content.all { it is PdfTextRun }, "List images are outside the proof")
                val markerFace = resolveRun(entry.level.one("rPr"), base)
                PdfListLabel(entry.value, PdfTextStyle(markerFace.id(fontId), markerFace.size ?: reject("Missing marker size"), markerFace.color, markerFace.languageTag), entry.alignment, entry.suffix, geometry.listTab, defaultTabInterval)
            }
            val alignment = if (geometry.direction == PdfTextDirection.RIGHT_TO_LEFT) when (geometry.alignment) {
                PdfAlignment.LEFT -> PdfAlignment.RIGHT; PdfAlignment.RIGHT -> PdfAlignment.LEFT; PdfAlignment.CENTER -> PdfAlignment.CENTER
            } else geometry.alignment
            check(geometry.direction != PdfTextDirection.RIGHT_TO_LEFT || geometry.left == 0f && geometry.right == 0f, "RTL source indentation is not qualified")
            return PdfParagraph(content, geometry.before, geometry.after, PdfParagraphFormat(alignment, geometry.left, geometry.right,
                geometry.first, geometry.line, true,
                geometry.keepWithNext, geometry.keepLines, geometry.widowControl, geometry.pageBreakBefore, geometry.outline,
                if (geometry.tabStops.isNotEmpty() || content.any { it is PdfTab }) PdfTabSettings(geometry.tabStops, defaultTabInterval, decimalSeparator) else null, geometry.automatic, geometry.direction), label)
        }
        fun readTable(table: XmlElement, partRelations: Map<String, String>, fields: Boolean, depth: Int = 0): PdfTable {
            check(depth <= 1, "Only one nested table level is supported")
            children(table, setOf("tblPr", "tblGrid", "tr"))
            val properties = table.one("tblPr") ?: reject("Explicit table properties required")
            children(properties, setOf("tblStyle", "tblW", "tblInd", "tblLayout", "tblBorders", "tblCellMar"))
            val tableStyles = properties.one("tblStyle")?.let { chain(it.value("val") ?: reject("Missing table style"), "table") } ?: emptyList()
            val layout = properties.one("tblLayout")?.value("type")
            check(layout in setOf("fixed", "autofit"), "Explicit qualified table layout required")
            val naturalWidths = layout == "autofit"
            check(!naturalWidths || depth == 0, "Natural widths in nested tables are not qualified")
            val grid = table.one("tblGrid") ?: reject("Missing table grid")
            check(grid.elements().all { it.name.namespaceURI == word && it.name.localPart == "gridCol" }, "Unsupported table grid")
            val widths = grid.elements().map { twips(it.value("w")) }
            check(widths.size in 1..16, "Invalid table grid")
            fun dimension(node: XmlElement): Float {
                attributes(node, setOf("w", "type"))
                check(node.value("type") == "dxa", "Only physical table dimensions are supported")
                return twips(node.value("w"))
            }
            fun automaticDimension(node: XmlElement) {
                attributes(node, setOf("w", "type")); children(node, emptySet())
                check(node.value("type") == "auto" && node.value("w") == "0", "Natural widths require automatic table and cell dimensions")
            }
            val tableWidth = properties.one("tblW") ?: reject("Missing table width")
            if (naturalWidths) automaticDimension(tableWidth)
            else check(kotlin.math.abs(widths.sum() - dimension(tableWidth)) < 0.01f, "Table width differs from grid")
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
            val border = borders.firstOrNull()
            fun ranked(value: PdfBorder): PdfEdgeStroke {
                val units = value.width * 8
                check(units == units.toInt().toFloat(), "Cell-border fallback must use integral eighth points")
                val c = value.color
                val priority = (units.toLong() shl 32) or ((1020 - c.red - c.blue - 2 * c.green).toLong() shl 22) or
                    ((765 - c.blue - 2 * c.green).toLong() shl 12) or (255 - c.green).toLong()
                return PdfEdgeStroke(value, priority)
            }
            val fallbackStroke by lazy { border?.let(::ranked) }
            val fallbackBorders by lazy { PdfCellBorders(fallbackStroke, fallbackStroke, fallbackStroke, fallbackStroke) }
            fun readCellBorders(node: XmlElement): PdfCellBorders {
                attributes(node, emptySet()); children(node, setOf("top", "right", "bottom", "left"))
                fun stroke(name: String): PdfEdgeStroke? {
                    val edge = node.one(name) ?: return fallbackStroke
                    attributes(edge, setOf("val", "sz", "color")); children(edge, emptySet())
                    edge.value("sz")?.let { value -> val size = value.toIntOrNull(); check(size != null && size in 1..32, "Invalid cell border width") }
                    edge.value("color")?.let(::color)
                    return when (edge.value("val")) {
                        "none" -> fallbackStroke
                        "nil" -> null
                        "single" -> {
                            val size = edge.value("sz")?.toIntOrNull()?.takeIf { it in 1..32 } ?: reject("Invalid cell border width")
                            ranked(PdfBorder(size / 8f, color(edge.value("color"))))
                        }
                        else -> reject("Only solid cell borders are qualified")
                    }
                }
                return PdfCellBorders(stroke("top"), stroke("right"), stroke("bottom"), stroke("left"))
            }
            val rowNodes = table.elements().filter { it.name.localPart == "tr" }
            val headerFlags = mutableListOf<Boolean>()
            data class CellSpec(val blocks: List<PdfBlock>, val background: PdfColor?, val span: Int, val alignment: PdfVerticalAlignment, val borders: PdfCellBorders?, var rows: Int = 1)
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
                    children(cp, setOf("tcW", "shd", "gridSpan", "vMerge", "vAlign", "tcBorders"))
                    val cellBorders = cp.one("tcBorders")?.let(::readCellBorders)
                    val alignment = cp.one("vAlign")?.let { node ->
                        attributes(node, setOf("val")); children(node, emptySet())
                        when (node.value("val")) { "top" -> PdfVerticalAlignment.TOP; "center" -> PdfVerticalAlignment.CENTER; "bottom" -> PdfVerticalAlignment.BOTTOM; else -> reject("Unsupported cell vertical alignment") }
                    } ?: PdfVerticalAlignment.TOP
                    val span = cp.one("gridSpan")?.let { attributes(it, setOf("val")); number(it.value("val")).toInt() } ?: 1
                    check(span in 1..16 && column + span <= widths.size, "Invalid grid span")
                    val cellWidth = cp.one("tcW") ?: reject("Missing cell width")
                    if (naturalWidths) automaticDimension(cellWidth)
                    else check(kotlin.math.abs(dimension(cellWidth) - widths.subList(column, column + span).sum()) < 0.01f, "Inconsistent cell width")
                    val background = cp.one("shd")?.let {
                        attributes(it, setOf("val", "color", "fill"))
                        check(it.value("val") == "clear" && it.value("color") == "auto", "Unsupported cell shading")
                        color(it.value("fill"))
                    }
                    val blocks = cell.elements().filter { it.name.localPart != "tcPr" }.map { child ->
                        when (child.name.localPart) { "p" -> readParagraph(child, partRelations, fields, tableStyles); "tbl" -> readTable(child, partRelations, fields, depth + 1); else -> reject("Unsupported cell block") }
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
                            check(cp.one("vAlign") == null || origin.alignment == alignment, "Inconsistent vertical continuation alignment")
                            check(cellBorders == null || origin.borders == cellBorders, "Inconsistent vertical continuation borders")
                            check(blocks.all { it is PdfParagraph && it.runs.all { run -> run is PdfTextRun && run.text.isBlank() } }, "Continuation cells must not contain additional content")
                            origin.rows++
                            active[column] = origin
                        }
                        null, "restart" -> {
                            val spec = CellSpec(blocks, background, span, alignment, cellBorders)
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
            val customBorders = specs.any { (cells, _) -> cells.any { it.borders != null } }
            val rows = specs.map { (cells, allow) -> PdfTableRow(cells.map { PdfTableCell(it.blocks, it.background, it.span, it.rows, it.alignment, if (customBorders) it.borders ?: fallbackBorders else null) }, allow, splitOversized = true) }
            if (customBorders) {
                val styles = rows.flatMap { it.cells }.flatMap { cell -> cell.borders!!.let { listOfNotNull(it.top, it.right, it.bottom, it.left) } }.map { it.border }.distinct()
                check(styles.size <= 1, "Conflicting cell-border colors and widths are not qualified")
            }
            val count = headerFlags.takeWhile { it }.size
            check(headerFlags.drop(count).none { it }, "Table headers must be leading rows")
            val indent = properties.one("tblInd")?.let(::dimension) ?: reject("Explicit table indent required")
            val physicalIndent = if (depth == 0) indent - padding.left else indent + (border?.width ?: 0f) / 2
            return PdfTable(widths, rows, padding, border, count, physicalIndent, if (naturalWidths) PdfTableWidthPolicy.FIT_CONTENTS else PdfTableWidthPolicy.FIXED)
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
                        if (child is XmlElement && child.name.localPart == "pPr" && block in sectionEnds) {
                            XmlElement(child.name, child.attributes, child.namespaces).also { copy -> copy.children += child.children.filterNot { it is XmlElement && it.name.namespaceURI == word && it.name.localPart == "sectPr" } }
                        } else if (child is XmlElement && child.name.localPart == "r" && child.elements().any { it in frames }) {
                            check(child.elements().all { it in frames || it.name.localPart == "rPr" }, "Mixed frame/text runs are unsupported"); null
                        } else child
                    }
                    numbering.alias(block, plain)
                    neighbors[plain] = neighbors[block] ?: (null to null)
                    frames.map { if (it.name.localPart == "pict") readBox(it, partRelations) else readDrawingBox(it, partRelations) } + readParagraph(plain, partRelations, fields)
                }
                "tbl" -> listOf(readTable(block, partRelations, fields))
                else -> reject("Unsupported block")
            }
        }
        fun band(name: String, reference: XmlElement?): PdfPageBand? {
            if (reference == null) return null
            check(reference.attributes.keys.all { it.namespaceURI == word && it.localPart == "type" || it.namespaceURI == rel && it.localPart == "id" }, "Unsupported page-band reference attribute")
            children(reference, emptySet())
            check(reference.value("type") in setOf("default", "first", "even"), "Unsupported page-band type")
            val target = relations[reference.attribute(rel, "id")] ?: reject("Missing page-band relationship")
            check(target.matches(Regex("${name}[1-9][0-9]?\\.xml")), "Wrong page-band relationship")
            val bandRoot = part("word/$target")
            registerNeighbors(bandRoot)
            check(bandRoot.name.namespaceURI == word && bandRoot.name.localPart == if (name == "header") "hdr" else "ftr", "Invalid page band")
            children(bandRoot, setOf("p", "tbl"))
            val ownRelations = parts["word/_rels/$target.rels"]?.let { readRelations(xml.read(it)) } ?: emptyMap()
            val offset = twips(margins.value(name))
            val extent = (if (name == "header") page.margins.top else page.margins.bottom) - offset
            return PdfPageBand(offset, extent, readBlocks(bandRoot, ownRelations, true))
        }
        val headerReferences = mutableMapOf<String, XmlElement>()
        val footerReferences = mutableMapOf<String, XmlElement>()
        val sequences = ranges.map { (section, content) ->
            val geometry = readGeometry(section); page = geometry.first; margins = geometry.second
            for ((name, references) in listOf("header" to headerReferences, "footer" to footerReferences)) {
                for (reference in section.elements().filter { it.name.localPart == name + "Reference" }) {
                    references[reference.value("type")!!] = reference
                }
            }
            val range = XmlElement(body.name, body.attributes, body.namespaces).also { it.children += content }
            val blocks = readBlocks(range, relations, false)
            val header = band("header", headerReferences["default"])
            val footer = band("footer", footerReferences["default"])
            val first = if (section.one("titlePg")?.let(::pageToggle) == true)
                PdfPageBands(band("header", headerReferences["first"]), band("footer", footerReferences["first"])) else null
            val even = if (evenAndOddHeaders)
                PdfPageBands(band("header", headerReferences["even"]), band("footer", footerReferences["even"])) else null
            val flow = PdfFlow(header, footer, firstPageBands = first, evenPageBands = even)
            val numbering = pageNumbering(section)
            PdfPageSequence(page, blocks, flow, numbering.copy(alignParity = evenAndOddHeaders && numbering.start != null))
        }
        return PdfDocument(sequences, if (sequences.size == 1) sequences.single().flow?.maxPages ?: 1 else 32)
    }

    private data class Face(val family: String? = null, val size: Float? = null, val bold: Boolean = false,
        val italic: Boolean = false, val color: PdfColor = PdfColor(0, 0, 0), val languageTag: String? = null, val rtl: Boolean? = null) {
        fun id(resolve: (String, Boolean, Boolean) -> String): String = resolve(
            requireNotNull(family) { "An explicit font family is required" }, bold, italic)
    }

    private fun runProperties(property: XmlElement?, initial: Face, themeFonts: Map<String, String>): Face {
        if (property == null) return initial
        children(property, setOf("rStyle", "rFonts", "sz", "b", "i", "color", "lang", "szCs", "bCs", "iCs", "rtl"))
        var result = initial
        property.one("numPr")?.let { node ->
            attributes(node, emptySet()); children(node, setOf("numId", "ilvl")); check(node.elements().isNotEmpty(), "Empty numbering reference")
            node.elements().forEach { leaf -> attributes(leaf, setOf("val")); check(leaf.elements().isEmpty(), "Invalid numbering reference"); check(number(leaf.value("val")) <= if (leaf.name.localPart == "ilvl") 8 else 1_000_000, "Numbering reference exceeds bounds") }
        }
        property.one("rFonts")?.let {
            attributes(it, setOf("ascii", "hAnsi", "asciiTheme", "hAnsiTheme", "cs"))
            fun family(name: String): String? = it.value(name + "Theme")?.let { key ->
                val kind = when (key) { "majorAscii", "majorHAnsi" -> "major"; "minorAscii", "minorHAnsi" -> "minor"; else -> reject("Unsupported theme font role") }
                themeFonts[kind] ?: reject("Unresolved theme font")
            } ?: it.value(name) ?: initial.family
            val family = family("ascii") ?: reject("Explicit Latin font required")
            check(family("hAnsi") == family, "Mixed font families are unsupported")
            check(it.value("cs") == null || it.value("cs") == family, "Separate complex-script font faces are outside this proof")
            result = result.copy(family = family)
        }
        property.one("lang")?.let {
            attributes(it, setOf("val")); check(it.elements().isEmpty(), "Invalid run language")
            val language = it.value("val") ?: reject("Missing run language")
            // Validate using the shared language contract; source mapping remains format-specific.
            PdfTextStyle("regular", 11f, languageTag = language)
            result = result.copy(languageTag = language)
        }
        property.one("sz")?.let { result = result.copy(size = number(it.value("val")) / 2) }
        fun enabled(node: XmlElement): Boolean = when (node.value("val")) { null, "true", "1", "on" -> true; "false", "0", "off" -> false; else -> reject("Invalid toggle") }
        property.one("rtl")?.let { attributes(it, setOf("val")); check(it.elements().isEmpty(), "Invalid run direction"); result = result.copy(rtl = enabled(it)) }
        property.one("b")?.let { result = result.copy(bold = enabled(it)) }
        property.one("i")?.let { result = result.copy(italic = enabled(it)) }
        property.one("szCs")?.let { attributes(it, setOf("val")); check(it.elements().isEmpty() && number(it.value("val")) / 2 == result.size, "Separate complex-script sizes are not qualified") }
        property.one("bCs")?.let { attributes(it, setOf("val")); check(it.elements().isEmpty() && enabled(it) == result.bold, "Separate complex-script weight is not qualified") }
        property.one("iCs")?.let { attributes(it, setOf("val")); check(it.elements().isEmpty() && enabled(it) == result.italic, "Separate complex-script slant is not qualified") }
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
        val keepWithNext: Boolean = false, val keepLines: Boolean = false, val widowControl: Boolean = true, val pageBreakBefore: Boolean = false, val outline: Int? = null, val listTab: Float? = null, val tabStops: List<PdfTabStop> = emptyList(), val automatic: PdfAutomaticLineSpacing? = PdfAutomaticLineSpacing(), val contextual: Boolean = false, val direction: PdfTextDirection? = null)

    private fun paragraphProperties(property: XmlElement, initial: Geometry): Geometry {
        children(property, setOf("pStyle", "bidi", "contextualSpacing", "spacing", "jc", "ind", "widowControl", "keepNext", "keepLines", "pageBreakBefore", "numPr", "outlineLvl", "tabs"))
        var result = initial
        fun flag(node: XmlElement): Boolean {
            attributes(node, setOf("val"))
            return when (node.value("val")) { null, "true", "1", "on" -> true; "false", "0", "off" -> false; else -> reject("Invalid paragraph toggle") }
        }
        property.one("bidi")?.let { check(it.elements().isEmpty(), "Invalid paragraph direction"); result = result.copy(direction = if (flag(it)) PdfTextDirection.RIGHT_TO_LEFT else PdfTextDirection.LEFT_TO_RIGHT) }
        property.one("outlineLvl")?.let { attributes(it, setOf("val")); val level = number(it.value("val")).toInt(); check(level in 0..8, "Unsupported outline level"); result = result.copy(outline = level) }
        property.one("tabs")?.let { tabs ->
            children(tabs, setOf("tab"))
            val merged = result.tabStops.associateBy { it.position }.toMutableMap()
            val positions = mutableSetOf<Float>()
            var numTab = result.listTab
            for (tab in tabs.elements()) {
                attributes(tab, setOf("val", "pos", "leader")); check(tab.elements().isEmpty(), "Invalid tab stop")
                val pos = twips(tab.value("pos")); check(positions.add(pos), "Duplicate source tab position")
                if (tab.value("val") == "clear") { merged.remove(pos); if (numTab == pos) numTab = null; continue }
                val kind = when (tab.value("val")) {
                    "left", "num" -> PdfTabAlignment.LEFT; "right" -> PdfTabAlignment.RIGHT
                    "center" -> PdfTabAlignment.CENTER; "decimal" -> PdfTabAlignment.DECIMAL
                    else -> reject("Unsupported tab alignment")
                }
                val leader = when (tab.value("leader") ?: "none") {
                    "none" -> PdfTabLeader.NONE; "dot" -> PdfTabLeader.DOT; "hyphen" -> PdfTabLeader.HYPHEN; "underscore" -> PdfTabLeader.UNDERSCORE
                    else -> reject("Unsupported tab leader")
                }
                merged[pos] = PdfTabStop(pos, kind, leader)
                if (tab.value("val") == "num") numTab = pos
            }
            result = result.copy(listTab = numTab, tabStops = merged.values.sortedBy { it.position })
        }
        property.one("keepNext")?.let { result = result.copy(keepWithNext = flag(it)) }
        property.one("keepLines")?.let { result = result.copy(keepLines = flag(it)) }
        property.one("widowControl")?.let { result = result.copy(widowControl = flag(it)) }
        property.one("pageBreakBefore")?.let { result = result.copy(pageBreakBefore = flag(it)) }
        property.one("contextualSpacing")?.let { result = result.copy(contextual = flag(it)) }
        property.one("spacing")?.let {
            attributes(it, setOf("before", "after", "line", "lineRule"))
            if (it.value("line") != null || it.value("lineRule") != null) {
                val line = it.value("line") ?: reject("Missing line spacing")
                result = when (it.value("lineRule") ?: "auto") {
                    "exact" -> result.copy(line = twips(line), automatic = null)
                    "auto" -> result.copy(line = null, automatic = PdfAutomaticLineSpacing(number(line) / 240f))
                    else -> reject("Unsupported line spacing policy")
                }
            }
            result = result.copy(before = it.value("before")?.let(::twips) ?: result.before,
                after = it.value("after")?.let(::twips) ?: result.after)
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
        val allowed = mapOf(wp to setOf("inline", "extent", "effectExtent", "docPr", "cNvGraphicFramePr"), drawing to setOf("graphic", "graphicData", "graphicFrameLocks", "blip", "stretch", "fillRect", "xfrm", "off", "ext", "prstGeom", "avLst"),
            picture to setOf("pic", "nvPicPr", "cNvPr", "cNvPicPr", "blipFill", "spPr"))
        check(inline.descendants().all { it.name.localPart in (allowed[it.name.namespaceURI] ?: emptySet()) }, "Unsupported drawing effect or geometry")
        inline.descendants().filter { it.name.namespaceURI == drawing && it.name.localPart == "graphicFrameLocks" }.forEach { locks ->
            check(locks.attributes.all { it.key.namespaceURI.isEmpty() && it.key.localPart == "noChangeAspect" && it.value in setOf("1", "true") } && locks.elements().isEmpty(), "Unsupported image editing lock")
        }
        inline.elements().filter { it.name.namespaceURI == wp && it.name.localPart == "effectExtent" }.forEach { effect ->
            check(effect.attributes.all { it.key.namespaceURI.isEmpty() && it.key.localPart in setOf("l", "t", "r", "b") && it.value == "0" } && effect.elements().isEmpty(), "Nonzero image effects are unsupported")
        }
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
        budget(bytes.size <= minOf(8_388_608L, context.limits.maxTemplateBytes))
        val parts = linkedMapOf<String, ByteArray>()
        var total = 0
        try {
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    context.checkActive()
                    val entry = zip.nextEntry ?: break
                    budget(parts.size < minOf(64, context.limits.maxArchiveEntries))
                    check(entry.name !in parts && !entry.isDirectory, "Invalid or duplicate ZIP entries")
                    check(entry.name.matches(Regex("(?:\\[Content_Types]\\.xml|_rels/\\.rels|docProps/(?:app|core)\\.xml|word/(?:(?:document|styles|numbering|settings|fontTable|webSettings|(?:header|footer)[1-9][0-9]?)\\.xml|theme/theme1\\.xml|_rels/(?:document|(?:header|footer)[1-9][0-9]?)\\.xml\\.rels|media/[A-Za-z0-9_.-]+\\.png))")), "Unsupported package part")
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        context.checkActive()
                        val read = zip.read(buffer)
                        if (read < 0) break
                        total += read
                        budget(total <= minOf(8_388_608L, context.limits.maxExpandedBytes) && output.size() + read <= 4_194_304)
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
    private fun pageNumbering(section: XmlElement): PdfPageNumbering {
        val node = section.one("pgNumType") ?: return PdfPageNumbering()
        attributes(node, setOf("start", "fmt")); children(node, emptySet())
        check(node.value("fmt") in setOf(null, "decimal"), "Only decimal page numbering is qualified")
        val start = node.value("start")?.let { value -> value.toIntOrNull()?.takeIf { it in 0..9_999 } ?: reject("Invalid page-number start") }
        return PdfPageNumbering(start)
    }

    private fun pageToggle(node: XmlElement): Boolean {
        attributes(node, setOf("val")); children(node, emptySet())
        return when (node.value("val")) { null, "1", "true", "on" -> true; "0", "false", "off" -> false; else -> reject("Invalid page-policy toggle") }
    }
    private fun children(node: XmlElement, allowed: Set<String>) {
        activePart = provenance[node] ?: activePart
        check(node.elements().all { it.name.namespaceURI == word && it.name.localPart in allowed }, "Unsupported document feature")
        check(node.elements().groupBy { it.name.localPart }.all { (name, values) -> name in setOf("r", "p", "style", "tbl", "tr", "tc", "fldSimple", "t", "tab", "headerReference", "footerReference") || values.size == 1 }, "Duplicate property")
    }
    private fun attributes(node: XmlElement, allowed: Set<String>) {
        activePart = provenance[node] ?: activePart
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
    private fun budget(condition: Boolean) {
        if (!condition) throw dev.nuaforma.contracts.GenerationException(listOf(dev.nuaforma.contracts.Diagnostic(
            dev.nuaforma.contracts.DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, dev.nuaforma.contracts.RejectionReason.PDF_CONVERSION_BYTE_BUDGET_WAS_EXCEEDED)))
    }
    private fun check(condition: Boolean, message: String) { if (!condition) reject(message) }
    private fun reject(message: String): Nothing = throw NativeDocxReadException(message, activePart)
}

internal class NativeDocxReadException(message: String, val part: String) : IllegalArgumentException(message)
