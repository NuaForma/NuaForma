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
    private val source = NativeDocxSource(context)
    private val parsed = linkedMapOf<String, XmlElement>()
    private val jpegs = NativeJpegImages(context)
    private var metafiles = dev.nuaforma.docx.pdf.adapters.nativeengine.emf.NativeEmfImages(context, null, false)
    private val lineReader = NativeDocxLines({ node -> source.current = node; activePart = provenance[node] ?: activePart }, ::reject)
    private val vectorReader = NativeDocxVectors({ node -> source.current = node; activePart = provenance[node] ?: activePart }, ::reject)

    private fun parse(bytes: ByteArray, part: String): XmlElement {
        activePart = part
        return parsed.getOrPut(part) { xml.read(bytes).also { root ->
            source.index(root, part)
            source.current = root
            root.descendants().forEach { provenance[it] = part }
        } }.also { source.current = it }
    }

    fun read(bytes: ByteArray, imageFonts: ((String, Boolean, Boolean) -> java.awt.Font)? = null, inspectImages: Boolean = false): PdfDocument {
        metafiles = dev.nuaforma.docx.pdf.adapters.nativeengine.emf.NativeEmfImages(context, imageFonts, inspectImages)
        return try { readDocument(bytes) }
        catch (error: NativeDocxReadException) { throw error }
        catch (_: IllegalArgumentException) { throw source.failure("Unsupported combination of native document formatting", activePart) }
    }

    private fun readDocument(bytes: ByteArray): PdfDocument {
        val parts = unpack(bytes)
        parts.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.forEach { (name, content) -> parse(content, name) }
        WordSensitivityMetadata(context).removeFromPrintModel(parsed)
        val propertyValues = WordPackageMetadata(context).removeFromPrintModel(parsed)
        val bibliography = WordBibliographyMetadata(context).removeFromPrintModel(parsed)
        bibliography.forEach { parsed.remove(it) }
        val formatting = WordSettingsMetadata(context).normalize(parsed) { name, node ->
            source.current = node
            source.failure("Unsupported Word document settings", name)
        }
        parsed["word/numbering.xml"]?.let { root -> WordNumberingMetadata(context).normalize(root) { node ->
            source.current = node
            source.failure("Unsupported Word numbering metadata", "word/numbering.xml")
        } }
        val compatibilityModes = parsed["word/settings.xml"]?.elements().orEmpty()
            .filter { it.name.namespaceURI == word && it.name.localPart == "compat" }
            .flatMap { it.elements() }.filter { it.name.namespaceURI == word && it.name.localPart == "compatSetting" &&
                it.value("name") == "compatibilityMode" && it.value("uri") == "http://schemas.microsoft.com/office/word" }
        if (compatibilityModes.size > 1) throw NativeDocxReadException("Ambiguous document compatibility mode", "word/settings.xml")
        val modernTableAlignment = compatibilityModes.singleOrNull()?.value("val") == "15"
        val complexText = WordAuthoringMetadata.containsComplexText(parsed.values)
        parsed.forEach { (name, root) -> WordAuthoringMetadata.normalize(root, name, complexText) { node ->
            source.current = node
            source.failure("Unsupported Word authoring or compatibility settings", name)
        } }
        parsed["word/webSettings.xml"]?.let { root -> WordWebSettings(context).normalize(root) { node ->
            source.current = node
            source.failure("Unsupported Word web settings", "word/webSettings.xml")
        } }
        val staticFields = NativeWordValues(context, propertyValues, parsed["docProps/core.xml"], { node ->
            source.current = node; activePart = provenance[node] ?: activePart
        }, ::reject)
        val propertyParagraphs = NativePropertyParagraphs(context, staticFields, { source.current = it }, { copy, original ->
            provenance[copy] = provenance[original] ?: activePart; source.alias(copy, original)
        }, ::reject)
        parsed.forEach { (name, root) -> activePart = name; propertyParagraphs.normalize(root) }
        val formFields = java.util.IdentityHashMap<XmlElement, NativeDocxFields.Form>()
        parsed.forEach { (name, root) ->
            activePart = name; source.current = root
            NativeDocxFields.normalize(root, ::reject, formFields, { field, original ->
                provenance[field] = name; source.alias(field, original)
            }) { source.current = it }
        }
        val blockBoundaryTextRanges = mutableSetOf<String>()
        parsed.forEach { (name, root) -> activePart = name; blockBoundaryTextRanges += NativeDocxBookmarks(context).normalize(root, { source.current = it }, ::reject) }
        staticFields.index(parsed.values)
        for (name in listOf("word/fontTable.xml", "word/webSettings.xml")) parsed[name]?.let { root ->
            activePart = name
            source.current = root
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
            source.current = section
            attributes(section, emptySet())
            children(section, setOf("pgSz", "pgMar", "headerReference", "footerReference", "type", "titlePg", "pgNumType", "footnotePr", "endnotePr"))
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
            attributes(size, setOf("w", "h", "orient", "code"))
            size.value("code")?.let(::number)
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
            if (element.attribute("", "Type") == "$rel/hyperlink") {
                check(element.attribute("", "TargetMode") == "External", "Hyperlink relationships must be external")
                PdfLink.External(target)
                return@associate id to target
            }
            check(target.matches(Regex("(?:media/[A-Za-z0-9_.-]+\\.(?i:png|emf|jpe?g)|styles\\.xml|numbering\\.xml|settings\\.xml|footnotes\\.xml|endnotes\\.xml|fontTable\\.xml|webSettings\\.xml|theme/theme1\\.xml|(?:header|footer)[1-9][0-9]?\\.xml)")), "Unsupported relationship target")
            val type = element.attribute("", "Type")
            check(type == "$rel/image" && target.startsWith("media/") || type == "$rel/styles" && target == "styles.xml" || type == "$rel/numbering" && target == "numbering.xml" || type == "$rel/settings" && target == "settings.xml" ||
                type == "$rel/footnotes" && target == "footnotes.xml" || type == "$rel/endnotes" && target == "endnotes.xml" ||
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
        var noteNumbering = NativeDocxNotes.defaults()

        fun noteProperties(root: XmlElement) {
            noteNumbering = noteNumbering.mapValues { (kind, previous) ->
                NativeDocxNotes.properties(root.one(NativeDocxNotes.kindName(kind) + "Pr"), kind, previous, ::reject)
            }
        }

        var decimalSeparator = "."
        var evenAndOddHeaders = false
        val defaultTabInterval = parts["word/settings.xml"]?.let { bytes ->
            val settings = parse(bytes, "word/settings.xml")
            check(settings.name.namespaceURI == word && settings.name.localPart == "settings", "Invalid settings root")
            attributes(settings, emptySet()); children(settings, setOf("defaultTabStop", "decimalSymbol", "evenAndOddHeaders", "footnotePr", "endnotePr"))
            noteProperties(settings)
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
            children(style, setOf("name", "basedOn", "pPr", "rPr") +
                if (type == "table") setOf("tblPr", "tcPr", "tblStylePr") else emptySet())
            val parent = style.one("basedOn")?.value("val")
            return (parent?.let { chain(it, type, seen + id) } ?: emptyList()) + style
        }

        check(defaultParagraph?.one("numPr") == null, "Numbering in document defaults is outside the proof")
        check(("word/numbering.xml" in parts) == relations.values.contains("numbering.xml"), "Numbering relationship/part mismatch")
        val numbering = NativeDocxNumbering(parts["word/numbering.xml"]?.let { parse(it, "word/numbering.xml") }, body) { paragraph ->
            chain(paragraph.one("pPr")?.one("pStyle")?.value("val") ?: normal, "paragraph")
        }
        noteProperties(ranges.first().first)
        val noteRoots = PdfNoteKind.entries.mapNotNull { kind ->
            val target = NativeDocxNotes.kindName(kind) + "s.xml"
            check(("word/$target" in parts) == relations.values.contains(target), "Notes relationship/part mismatch")
            parts["word/$target"]?.let { kind to parse(it, "word/$target") }
        }.toMap()
        val sourceNotes = NativeDocxNotes(noteRoots, noteNumbering, ::reject) { node ->
            source.current = node
            activePart = provenance[node] ?: activePart
        }
        var currentNoteKind: PdfNoteKind? = null
        val openBookmarks = mutableSetOf<Pair<String, String>>()
        val seenBookmarks = mutableSetOf<Pair<String, String>>()
        val bookmarkNames = mutableMapOf<Pair<String, String>, String>()
        val bookmarkRuns = mutableMapOf<String, MutableList<PdfInline>>()
        val crossParagraphBookmarks = mutableSetOf<String>()
        val textReferences = mutableSetOf<String>()
        val textReferenceSources = mutableMapOf<String, XmlElement>()
        var paragraphCounter = 0
        var fieldCounter = 0
        var linkCounter = 0
        var sequenceIndex = 0

        fun readParagraph(paragraph: XmlElement, partRelations: Map<String, String>, fields: Boolean = false, tableStyles: List<XmlElement> = emptyList()): PdfParagraph {
            source.current = paragraph
            context.checkActive()
            crossParagraphBookmarks += openBookmarks.mapNotNull { bookmarkNames[it] }
            val logicalId = "source-p${paragraphCounter++}"
            val lineDrawings = mutableListOf<PdfLineDrawing>()
            children(paragraph, setOf("pPr", "r", "fldSimple", "hyperlink", "bookmarkStart", "bookmarkEnd"))
            val direct = paragraph.one("pPr")
            val styleChain = chain(direct?.one("pStyle")?.value("val") ?: normal, "paragraph")
            val listEntry = numbering.entry(paragraph)
            val sourceProperties = styleChain.mapNotNull { it.one("pPr") } + listOfNotNull(direct)
            if (listEntry == null && sourceProperties.any { it.one("numPr") != null }) {
                check(sourceProperties.mapNotNull { it.one("numPr")?.one("numId")?.value("val") }.lastOrNull() == "0", "Numbering outside body paragraphs is not qualified")
            }
            val paragraphChain = listOfNotNull(defaultParagraph) + tableStyles.mapNotNull { it.one("pPr") } + listOfNotNull(listEntry?.level?.one("pPr")) + styleChain.mapNotNull { it.one("pPr") } + listOfNotNull(direct)
            val legacyTableHierarchy = tableStyles.isNotEmpty() && !modernTableAlignment && !formatting.overrideNormalTableStyle
            val tableJustification = tableStyles.any { it.one("pPr")?.one("jc") != null }
            var geometry = paragraphChain.fold(Geometry()) { result, property ->
                val resolved = paragraphProperties(property, result)
                if (legacyTableHierarchy && tableJustification && property === styles.getValue(normal).one("pPr") &&
                    property.one("jc")?.value("val") == "left") resolved.copy(alignment = result.alignment) else resolved
            }
            val automaticBefore = geometry.autoBefore && !formatting.additiveParagraphSpacing
            val automaticAfter = geometry.autoAfter && !formatting.additiveParagraphSpacing
            geometry = geometry.copy(before = if (automaticBefore) 14f else geometry.before,
                after = if (automaticAfter) 14f else geometry.after)
            if (geometry.contextual) {

                fun same(node: XmlElement?) = node != null && (node.one("pPr")?.one("pStyle")?.value("val") ?: normal) == (direct?.one("pStyle")?.value("val") ?: normal)

                val (previous, next) = neighbors[paragraph] ?: (null to null)
                geometry = geometry.copy(before = if (same(previous)) 0f else geometry.before, after = if (same(next)) 0f else geometry.after)
            }
            check(listEntry != null || geometry.listTab == null, "List tab without numbering")
            val tableSize = tableStyles.any { it.one("rPr")?.one("sz") != null }
            val base = (tableStyles + styleChain).fold(resolveRun(defaultRun, Face(openType = modernTableAlignment || formatting.openTypeFeatures))) { result, style ->
                val resolved = resolveRun(style.one("rPr"), result)
                if (legacyTableHierarchy && tableSize && style === styles.getValue(normal) && style.one("rPr")?.one("sz") != null &&
                    resolved.size in setOf(11f, 12f)) resolved.copy(size = result.size) else resolved
            }

            fun faceFor(properties: XmlElement?, initial: Face = base): Face {
                val characterStyle = properties?.one("rStyle")?.value("val")
                val inherited = characterStyle?.let { id ->
                    chain(id, "character").fold(initial) { acc, style -> resolveRun(style.one("rPr"), acc) }
                } ?: initial
                return resolveRun(properties, inherited)
            }

            val markProperties = paragraphChain.mapNotNull { it.one("rPr") }
            val markFace = markProperties.takeIf { it.isNotEmpty() }?.fold(base) { face, property ->
                val metrics = WordParagraphMark.metricProperties(property) { node, valid ->
                    source.current = node
                    check(valid, "Invalid paragraph-mark highlight")
                    check(listEntry == null || node.value("val") == "none", "Highlight on numbered paragraph marks is not qualified")
                }
                faceFor(metrics, face)
            }

            val pendingDestinations = mutableListOf<PdfDestination>()
            var contents: PdfContents? = null

            fun readRun(run: XmlElement, link: PdfLink? = null, logicalLink: String? = null): List<PdfInline> {
            source.current = run
                if (run.name.localPart == "fldSimple") {
                    attributes(run, setOf("instr", "dirty", "fldLock"))
                    check(run.value("fldLock") in setOf(null, "0", "false", "off"), "Locked field results are not qualified")
                    run.value("dirty")?.let { check(it in setOf("0", "1", "true", "false", "on", "off"), "Invalid field state") }
                    val instruction = NativeDocxFields.instruction(run.value("instr") ?: reject("Missing field instruction"), ::reject)
                    children(run, setOf("r"))
                    if (instruction.kind == "FORMCHECKBOX") {
                        val form = formFields[run] ?: reject("Checkbox field requires its own form metadata")
                        run.elements().forEach { children(it, setOf("rPr", "t", "sym")) }
                        check(link == null && pendingDestinations.isEmpty(), "Navigation on legacy checkboxes is outside this profile")
                        val face = faceFor(form.properties)
                        check(!face.underline && !face.strikethrough && !face.superscript && face.rtl != true,
                            "Decorated or forced complex-script checkbox fields are outside this profile")
                        return listOf(NativeWordCheckbox.read(form, face.size ?: reject("Missing checkbox font size"), face.bold, face.color, { source.current = it }, ::reject))
                    }
                    if (instruction.kind == "SET") {
                        run.elements().forEach { children(it, setOf("rPr", "t")) }
                        check(link == null && pendingDestinations.isEmpty(), "Navigation on SET assignments is outside this profile")
                        return emptyList()
                    }
                    if (instruction.kind == "TOC") {
                        check(!fields && currentNoteKind == null && link == null && pendingDestinations.isEmpty() && contents == null, "Invalid contents location")
                        check(instruction.directOutlineLevels || body.descendants().none { it.name.namespaceURI == word && it.name.localPart == "p" && it.one("pPr")?.one("outlineLvl") != null },
                            "Contents for directly assigned outline levels require the explicit outline switch")
                        val levels = (instruction.firstLevel..instruction.lastLevel).map { level ->
                            val style = styles.entries.singleOrNull { (_, style) -> style.value("type") == "paragraph" &&
                                (style.value("styleId").equals("TOC${level + 1}", true) || style.one("name")?.value("val").equals("toc ${level + 1}", true)) }
                                ?: reject("Explicit contents paragraph styles are required")
                            val sample = XmlElement(paragraph.name)
                            val props = XmlElement(javax.xml.namespace.QName(word, "pPr", "w"))
                            props.children += XmlElement(javax.xml.namespace.QName(word, "pStyle", "w"), linkedMapOf(javax.xml.namespace.QName(word, "val", "w") to style.key))
                            sample.children += props
                            val textRun = XmlElement(javax.xml.namespace.QName(word, "r", "w"))
                            textRun.children += XmlElement(javax.xml.namespace.QName(word, "t", "w"), children = mutableListOf(XmlText("Contents")))
                            sample.children += textRun
                            val resolved = readParagraph(sample, partRelations)
                            PdfContentsLevel(level, (resolved.runs.single() as PdfTextRun).style, resolved.format, resolved.spaceBefore, resolved.spaceAfter)
                        }
                        contents = PdfContents(levels, instruction.linked)
                        return emptyList()
                    }
                    val cached = run.elements()
                    check(cached.isNotEmpty(), "A field needs an explicit formatting sample")
                    cached.forEach { children(it, setOf("rPr", "t")) }
                    val face = faceFor(cached.first().one("rPr"))
                    check(cached.all { faceFor(it.one("rPr")) == face }, "Mixed field-result formatting is not qualified")
                    check(face.rtl != true, "Forced complex-script page fields are not qualified")
                    check(link == null, "Nested field hyperlinks are not qualified")
                    source.current = run
                    activePart = provenance[run] ?: activePart
                    val staticValue = staticFields.resolve(instruction)
                    if (staticValue == "") return emptyList()
                    val style = textStyle(face)
                    staticValue?.let { value ->
                        check(value.codePoints().noneMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HEBREW },
                            "Hebrew static fields are outside the qualified field direction profile")
                        val navigation = pendingDestinations.takeIf { it.isNotEmpty() }?.let { PdfTextNavigation(destinations = it.toList()) }.also { pendingDestinations.clear() }
                        return listOf(PdfTextRun(value, style, navigation))
                    }
                    if (fields) {
                        check(instruction.kind in setOf("PAGE", "NUMPAGES", "SECTIONPAGES"), "Only page counts in page bands are qualified")
                        check(face.copy(languageTag = base.languageTag, rtl = base.rtl) == base, "Page-field cache formatting must match its paragraph style")
                        check(pendingDestinations.isEmpty(), "Navigation on repeated page fields is not qualified")
                        return listOf(when (instruction.kind) { "PAGE" -> PdfPageNumber(style); "NUMPAGES" -> PdfPageCount(style); else -> PdfSequencePageCount(style) })
                    }
                    val navigation = pendingDestinations.takeIf { it.isNotEmpty() }?.let { PdfTextNavigation(destinations = it.toList()) }.also { pendingDestinations.clear() }
                    val id = "source-field${fieldCounter++}"
                    if (instruction.kind == "SEQ") return listOf(PdfSequenceField(id, requireNotNull(instruction.argument), style,
                        instruction.restart, instruction.repeat, instruction.headingReset, navigation))
                    val kind = when (instruction.kind) {
                        "REF" -> PdfReferenceKind.TEXT; "PAGEREF" -> PdfReferenceKind.PAGE; "PAGE" -> PdfReferenceKind.CURRENT_PAGE
                        "NUMPAGES" -> PdfReferenceKind.TOTAL_PAGES; else -> PdfReferenceKind.SEQUENCE_PAGES
                    }
                    val target = if (kind == PdfReferenceKind.SEQUENCE_PAGES) "sequence$sequenceIndex" else instruction.argument
                    if (kind == PdfReferenceKind.TEXT) {
                        textReferences += requireNotNull(target)
                        textReferenceSources.putIfAbsent(target, run)
                    }
                    return listOf(PdfReferenceField(id, kind, style, target, instruction.linked, navigation))
                }
                children(run, setOf("rPr", "t", "tab", "drawing", "pict", "footnoteReference", "endnoteReference", "footnoteRef", "endnoteRef"))
                val contents = run.elements().filter { it.name.localPart != "rPr" }
                if (contents.isEmpty()) return emptyList()
                val face = faceFor(run.one("rPr"))
                propertyParagraphs.formats[run]?.let { format ->
                    check(face.rtl != true && format.samples.all { faceFor(it.run.one("rPr")).copy(languageTag = face.languageTag) == face },
                        "Mixed property-result formatting is not qualified")
                }
                source.current = run
                activePart = provenance[run] ?: activePart
                return contents.mapNotNull { item -> when (item.name.localPart) {
                    "footnoteReference", "endnoteReference", "footnoteRef", "endnoteRef" -> {
                        val kind = if (item.name.localPart.startsWith("foot")) PdfNoteKind.FOOTNOTE else PdfNoteKind.ENDNOTE
                        val owning = item.name.localPart.endsWith("Reference")
                        check(!fields && link == null && face.rtl != true, "Invalid note reference location")
                        attributes(item, if (owning) setOf("id", "customMarkFollows") else emptySet())
                        children(item, emptySet())
                        val navigation = pendingDestinations.takeIf { it.isNotEmpty() }?.let { PdfTextNavigation(destinations = it.toList()) }.also { pendingDestinations.clear() }
                        if (owning) {
                            check(currentNoteKind == null && item.value("customMarkFollows") in setOf(null, "0", "false", "off"), "Custom or nested note references are not qualified")
                            sourceNotes.call(kind, item.value("id"), textStyle(face), navigation)
                        } else {
                            check(currentNoteKind == kind, "Note label outside its definition")
                            PdfNoteLabel(textStyle(face), navigation)
                        }
                    }
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
                        val destinations = if (text.isNotBlank()) pendingDestinations.toList().also { pendingDestinations.clear() } else emptyList()
                        val navigation = if (link != null || destinations.isNotEmpty()) PdfTextNavigation(link, destinations, logicalLink) else null
                        PdfTextRun(text.ifEmpty { " " }, textStyle(face), navigation)
                    }
                    "tab" -> { check(link == null, "Linked tabs are not qualified"); attributes(item, emptySet()); check(item.elements().isEmpty(), "Invalid tab control"); PdfTab(textStyle(face)) }
                    "pict", "drawing" -> { if (vectorReader.isVector(item)) {
                        check(link == null && pendingDestinations.isEmpty() && !face.underline && !face.strikethrough && !face.superscript,
                            "Navigation or text decorations on inline vectors are not qualified")
                        vectorReader.read(item)
                    } else if (lineReader.isLine(item)) {
                        check(link == null && pendingDestinations.isEmpty(), "Navigation on drawn lines is not qualified")
                        check(lineDrawings.size < 32, "Paragraph line budget exceeded")
                        lineDrawings += lineReader.drawing(item, page); null
                    } else { check(link == null && pendingDestinations.isEmpty() && !face.underline && !face.strikethrough, "Navigation or text decorations on pictures are not qualified"); PdfImageRun(image(item, partRelations, parts)) } }
                    else -> reject("Unsupported run content")
                } }
            }

            val runs = paragraph.elements().flatMap { node -> when (node.name.localPart) {
                "pPr" -> emptyList()
                "bookmarkStart" -> {
                    attributes(node, setOf("id", "name")); children(node, emptySet())
                    val id = node.value("id")?.takeIf { it.matches(Regex("[0-9]{1,9}")) } ?: reject("Invalid bookmark ID")
                    val key = activePart to id
                    check(seenBookmarks.add(key) && seenBookmarks.size <= 1_000, "Duplicate or excessive bookmarks")
                    openBookmarks += key
                    val name = node.value("name") ?: reject("Missing bookmark name")
                    check(name !in bookmarkRuns, "Duplicate bookmark name")
                    bookmarkNames[key] = name
                    bookmarkRuns[name] = mutableListOf()
                    pendingDestinations += PdfDestination(name, name.takeUnless { it.startsWith("_") })
                    check(pendingDestinations.size <= 32, "Excessive adjacent bookmarks")
                    emptyList()
                }
                "bookmarkEnd" -> {
                    attributes(node, setOf("id")); children(node, emptySet())
                    check(openBookmarks.remove(activePart to (node.value("id") ?: reject("Missing bookmark ID"))), "Unmatched bookmark end")
                    emptyList()
                }
                "hyperlink" -> {
                    check(node.attributes.keys.all { it.namespaceURI == rel && it.localPart == "id" || it.namespaceURI == word && it.localPart in setOf("anchor", "history") }, "Unsupported hyperlink attribute")
                    node.value("history")?.let { check(it in setOf("0", "1", "true", "false", "on", "off"), "Invalid hyperlink history") }
                    children(node, setOf("r"))
                    val id = node.attribute(rel, "id")
                    val anchor = node.value("anchor")
                    check((id == null) != (anchor == null), "Hyperlinks require one target")
                    val link = if (id == null) PdfLink.Internal(requireNotNull(anchor)) else PdfLink.External(partRelations[id] ?: reject("Missing hyperlink relationship"))
                    check(node.elements().isNotEmpty(), "Empty hyperlink")
                    val linkIdentity = "source-link${linkCounter++}"
                    node.elements().flatMap { readRun(it, link, linkIdentity) }
                }
                else -> readRun(node)
            }.also { converted ->
                for (key in openBookmarks) bookmarkNames[key]?.let { bookmarkRuns.getValue(it).addAll(converted) }
            } }
            check(pendingDestinations.isEmpty(), "Bookmark requires following visible paragraph text")
            val markStyle = markFace?.takeIf { geometry.automatic != null || runs.isEmpty() }?.let(::textStyle)
            val content = runs.ifEmpty { listOf(PdfTextRun(" ", markStyle ?: textStyle(base))) }
            check(content.none { it is PdfImageRun } || content.size == 1, "Mixed image/text paragraphs are outside the proof profile")
            val label = listEntry?.let { entry ->
                check(content.all { it is PdfTextRun || it is PdfReferenceField || it is PdfSequenceField }, "List images and inline tabs are outside the proof")
                val markerFace = resolveRun(entry.level.one("rPr"), base)
                PdfListLabel(entry.value, textStyle(markerFace), entry.alignment, entry.suffix, geometry.listTab, defaultTabInterval)
            }
            val alignment = if (geometry.direction == PdfTextDirection.RIGHT_TO_LEFT) when (geometry.alignment) {
                PdfAlignment.LEFT -> PdfAlignment.RIGHT; PdfAlignment.RIGHT -> PdfAlignment.LEFT; PdfAlignment.CENTER -> PdfAlignment.CENTER
            } else geometry.alignment
            check(geometry.direction != PdfTextDirection.RIGHT_TO_LEFT || geometry.left == 0f && geometry.right == 0f, "RTL source indentation is not qualified")
            val pageOffset = if (geometry.mirror) {
                check(label == null && content.none { it is PdfTab } && contents == null && geometry.direction != PdfTextDirection.RIGHT_TO_LEFT,
                    "Mirrored lists, tab stops, contents and RTL indentation are not qualified")
                PdfParagraphPageOffset(geometry.right - geometry.left + maxOf(0f, -geometry.first),
                    if (!modernTableAlignment && formatting.flipLegacyMirrorIndents) PdfPageSide.ODD else PdfPageSide.EVEN)
            } else null
            return PdfParagraph(content, geometry.before, geometry.after, PdfParagraphFormat(alignment, geometry.left, geometry.right,
                geometry.first, geometry.line, !formatting.additiveParagraphSpacing,
                geometry.keepWithNext, geometry.keepLines, geometry.widowControl, geometry.pageBreakBefore, geometry.outline,
                if (geometry.tabStops.isNotEmpty() || content.any { it is PdfTab }) PdfTabSettings(geometry.tabStops, defaultTabInterval, decimalSeparator) else null, geometry.automatic, geometry.direction), label,
                semantics = PdfBlockSemantics(logicalId, when {
                    contents != null -> PdfSemanticRole.CONTENTS
                    geometry.outline != null -> PdfSemanticRole.HEADING
                    styleChain.any { it.value("styleId").equals("Caption", true) || it.one("name")?.value("val").equals("caption", true) } -> PdfSemanticRole.CAPTION
                    else -> PdfSemanticRole.PARAGRAPH
                }, geometry.outline), contents = contents, borders = geometry.borders, lines = lineDrawings)
                .withSpacing(PdfParagraphSpacing(automaticBefore, modernTableAlignment, automaticAfter))
                .withPageOffset(pageOffset)
                .withMinimumLineStyle(markStyle ?: if (geometry.automatic != null &&
                    (content.singleOrNull() is PdfImageRun || content.singleOrNull() is PdfVectorRun)) textStyle(base) else null)
        }

        fun readTable(table: XmlElement, partRelations: Map<String, String>, fields: Boolean, depth: Int = 0): PdfTable {
            source.current = table
            check(depth <= 1, "Only one nested table level is supported")
            children(table, setOf("tblPr", "tblGrid", "tr"))
            val properties = table.one("tblPr") ?: reject("Explicit table properties required")
            children(properties, setOf("tblStyle", "tblW", "tblInd", "tblLayout", "tblBorders", "tblCellMar", "tblLook", "tblStyleRowBandSize", "tblStyleColBandSize"))
            val tableStyles = properties.one("tblStyle")?.let { chain(it.value("val") ?: reject("Missing table style"), "table") } ?: emptyList()
            val rowNodes = table.elements().filter { it.name.localPart == "tr" }
            val headerCount = rowNodes.takeWhile { row -> row.one("trPr")?.one("tblHeader")?.let { it.value("val") in setOf(null, "1", "true", "on") } == true }.size
            val regions = NativeDocxTableStyles(tableStyles, properties, rowNodes.size,
                table.one("tblGrid")?.elements()?.size ?: 0, headerCount, context) { node, message -> source.current = node; reject(message) }
            val layout = regions.property("tblLayout")?.value("type")
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

            val tableWidth = regions.property("tblW") ?: reject("Missing table width")
            if (naturalWidths) automaticDimension(tableWidth)
            else check(kotlin.math.abs(widths.sum() - dimension(tableWidth)) < 0.01f, "Table width differs from grid")
            val cellMargins = regions.property("tblCellMar") ?: reject("Explicit cell margins required")
            children(cellMargins, setOf("top", "right", "bottom", "left"))

            fun edge(name: String) = dimension(cellMargins.one(name) ?: reject("Missing cell margin"))

            val padding = PdfMargins(edge("top"), edge("right"), edge("bottom"), edge("left"))
            val borderNodes = regions.property("tblBorders") ?: reject("Explicit table borders required")
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

            fun readCellBorders(node: XmlElement, inherited: PdfCellBorders = fallbackBorders): PdfCellBorders {
                attributes(node, emptySet()); children(node, setOf("top", "right", "bottom", "left"))

                fun stroke(name: String): PdfEdgeStroke? {
                    val edge = node.one(name) ?: return when (name) { "top" -> inherited.top; "right" -> inherited.right; "bottom" -> inherited.bottom; else -> inherited.left }
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

            val headerFlags = mutableListOf<Boolean>()
            data class CellSpec(val blocks: List<PdfBlock>, val background: PdfColor?, val span: Int, val alignment: PdfVerticalAlignment, val borders: PdfCellBorders?, var rows: Int = 1)
            val specs = mutableListOf<Pair<List<CellSpec>, Boolean>>()
            var previous = mapOf<Int, CellSpec>()
            for ((rowIndex, row) in rowNodes.withIndex()) {
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
                    val cellStyles = regions.layers(rowIndex, column)
                    val cellProperties = cellStyles.mapNotNull { it.one("tcPr") } + cp
                    val edgePolicies = cellProperties.mapNotNull { it.one("tcBorders") }
                    val cellBorders = if (edgePolicies.isEmpty()) null else edgePolicies.fold(fallbackBorders) { inherited, node -> readCellBorders(node, inherited) }
                    val alignment = cellProperties.mapNotNull { it.one("vAlign") }.lastOrNull()?.let { node ->
                        attributes(node, setOf("val")); children(node, emptySet())
                        when (node.value("val")) { "top" -> PdfVerticalAlignment.TOP; "center" -> PdfVerticalAlignment.CENTER; "bottom" -> PdfVerticalAlignment.BOTTOM; else -> reject("Unsupported cell vertical alignment") }
                    } ?: PdfVerticalAlignment.TOP
                    val span = cp.one("gridSpan")?.let { attributes(it, setOf("val")); number(it.value("val")).toInt() } ?: 1
                    check(!regions.active || cp.one("vMerge") == null && span == 1, "Merged conditional table regions are not qualified")
                    check(span in 1..16 && column + span <= widths.size, "Invalid grid span")
                    val cellWidth = cp.one("tcW") ?: reject("Missing cell width")
                    if (naturalWidths) automaticDimension(cellWidth)
                    else check(kotlin.math.abs(dimension(cellWidth) - widths.subList(column, column + span).sum()) < 0.01f, "Inconsistent cell width")
                    val background = cellProperties.mapNotNull { it.one("shd") }.lastOrNull()?.let {
                        attributes(it, setOf("val", "color", "fill"))
                        check(it.value("val") == "clear" && it.value("color") == "auto", "Unsupported cell shading")
                        it.value("fill")?.takeUnless { fill -> fill == "auto" }?.let(::color)
                    }
                    val blocks = cell.elements().filter { it.name.localPart != "tcPr" }.map { child ->
                        when (child.name.localPart) { "p" -> readParagraph(child, partRelations, fields, cellStyles); "tbl" -> readTable(child, partRelations, fields, depth + 1); else -> reject("Unsupported cell block") }
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
            val indent = regions.property("tblInd")?.let(::dimension) ?: reject("Explicit table indent required")
            val physicalIndent = if (depth == 0 && !modernTableAlignment) indent - padding.left else indent + (border?.width ?: 0f) / 2
            return PdfTable(widths, rows, padding, border, count, physicalIndent, if (naturalWidths) PdfTableWidthPolicy.FIT_CONTENTS else PdfTableWidthPolicy.FIXED)
        }

        fun readBox(pict: XmlElement, partRelations: Map<String, String>, fields: Boolean = false): PdfTextBox {
            source.current = pict
            val vml = "urn:schemas-microsoft-com:vml"
            val word10 = "urn:schemas-microsoft-com:office:word"
            val shape = pict.elements().singleOrNull() ?: reject("Only one shape per drawing is supported")
            source.current = shape
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
            val paragraphs = content.elements().map { readParagraph(it, partRelations, fields) }
            source.current = shape
            return PdfTextBox(points(css["margin-left"]) + if (horizontal == "page") 0f else page.margins.left,
                points(css["margin-top"]) + if (vertical == "margin") page.margins.top else 0f,
                points(css["width"]), points(css["height"]), paragraphs,
                if (vertical == "text") PdfBoxAnchor.PARAGRAPH else PdfBoxAnchor.PAGE, angle,
                PdfMargins(inset[1], inset[2], inset[3], inset[0]),
                if (shape.attribute("", "filled") == "f") null else color(shape.attribute("", "fillcolor")?.removePrefix("#") ?: reject("Explicit frame fill required")),
                if (shape.attribute("", "stroked") == "f") null else PdfBorder(points(shape.attribute("", "strokeweight")), color(shape.attribute("", "strokecolor")?.removePrefix("#") ?: reject("Explicit frame border required"))),
                when (css["v-text-anchor"]) { "top" -> PdfVerticalAlignment.TOP; "middle" -> PdfVerticalAlignment.CENTER; "bottom" -> PdfVerticalAlignment.BOTTOM; else -> reject("Unsupported frame vertical alignment") },
                if (wrapNode == null) PdfBoxWrap.NONE else PdfBoxWrap.TOP_BOTTOM)
        }

        fun readDrawingBox(node: XmlElement, partRelations: Map<String, String>, fields: Boolean = false): PdfTextBox {
            source.current = node
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
            val paragraphs = content.elements().map { readParagraph(it, partRelations, fields) }
            source.current = node
            return PdfTextBox(offset("positionH"), offset("positionV"), numeric(extent.attribute("", "cx")) / 12700,
                numeric(extent.attribute("", "cy")) / 12700, paragraphs, PdfBoxAnchor.PAGE,
                (transform.attribute("", "rot")?.let(::numeric) ?: 0f) / 60000,
                PdfMargins(numeric(bodyProps.attribute("", "tIns")) / 12700, numeric(bodyProps.attribute("", "rIns")) / 12700,
                    numeric(bodyProps.attribute("", "bIns")) / 12700, numeric(bodyProps.attribute("", "lIns")) / 12700),
                fill(props), lineColor?.let { PdfBorder(numeric(line.attribute("", "w")) / 12700, it) },
                when (bodyProps.attribute("", "anchor")) { "t" -> PdfVerticalAlignment.TOP; "ctr" -> PdfVerticalAlignment.CENTER; "b" -> PdfVerticalAlignment.BOTTOM; else -> reject("Unsupported vertical alignment") })
        }

        fun readAnchoredImage(node: XmlElement, partRelations: Map<String, String>, pageBand: Boolean = false): PdfAnchoredImage {
            source.current = node
            val anchor = node.elements().single()

            fun one(name: String) = anchor.elements().singleOrNull { it.name.namespaceURI == wp && it.name.localPart == name } ?: reject("Missing or duplicate image anchor property")

            val keys = setOf("distT", "distB", "distL", "distR", "simplePos", "relativeHeight", "behindDoc", "locked", "layoutInCell", "allowOverlap")
            check(anchor.attributes.keys.all { it.namespaceURI.isEmpty() && it.localPart in keys }, "Unsupported image anchor attribute")
            for ((key, expected) in mapOf("simplePos" to false, "behindDoc" to false, "locked" to false, "layoutInCell" to true, "allowOverlap" to true)) {
                val value = when (anchor.attribute("", key)) { "0", "false" -> false; "1", "true" -> true; else -> null }
                check(value == expected, "Unsupported image anchor policy")
            }
            check(anchor.attribute("", "relativeHeight")?.toLongOrNull()?.let { it in 0..4_294_967_295L } == true, "Invalid drawing order")
            val simple = one("simplePos")
            check(simple.attributes.size == 2 && simple.attribute("", "x") == "0" && simple.attribute("", "y") == "0", "Unsupported simple image position")
            val extent = one("extent")
            val imageWidth = number(extent.attribute("", "cx")) / 12700

            fun offset(name: String, horizontal: Boolean): Pair<Float, PdfBoxAnchor> {
                val position = one(name)
                check(position.attributes.size == 1 && position.elements().size == 1, "Unsupported anchor position")
                val relative = position.attribute("", "relativeFrom")
                check(relative in (if (horizontal) setOf("page", "margin", "column") else setOf("page", "paragraph")), "Unsupported image reference area")
                val value = position.elements().single()
                check(value.name.namespaceURI == wp && value.attributes.isEmpty() && value.elements().isEmpty(), "Invalid anchor offset")
                val text = value.children.filterIsInstance<XmlText>().joinToString("") { it.text }
                val base = if (horizontal && relative != "page") page.margins.left else 0f
                val available = if (relative == "page") page.width else page.width - page.margins.left - page.margins.right
                val amount = when (value.name.localPart) {
                    "posOffset" -> (text.toLongOrNull()?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }
                        ?: reject("Invalid signed image offset")).toFloat() / 12700
                    "align" -> {
                        check(horizontal, "Vertical image alignment is not qualified")
                        when (text) { "left" -> 0f; "right" -> available - imageWidth; "center" -> (available - imageWidth) / 2; else -> reject("Unsupported image alignment") }
                    }
                    else -> reject("Unsupported image offset")
                }
                return (base + amount) to if (relative == "paragraph") PdfBoxAnchor.PARAGRAPH else PdfBoxAnchor.PAGE
            }

            val horizontal = offset("positionH", true).first
            val vertical = offset("positionV", false)
            val wraps = anchor.elements().filter { it.name.namespaceURI == wp && it.name.localPart.startsWith("wrap") }
            check(wraps.size == 1 && wraps.single().elements().isEmpty(), "Unsupported image wrapping geometry")
            val wrap = wraps.single()
            val policy = when (wrap.name.localPart) {
                "wrapNone" -> { check(wrap.attributes.isEmpty(), "Invalid image wrapping"); PdfImageWrap.NONE }
                "wrapTopAndBottom" -> { check(wrap.attributes.isEmpty(), "Invalid image wrapping"); PdfImageWrap.TOP_BOTTOM }
                "wrapSquare" -> {
                    check(wrap.attributes.size == 1, "Unsupported square wrapping effect")
                    when (wrap.attribute("", "wrapText")) {
                        "left" -> PdfImageWrap.LEFT; "right" -> PdfImageWrap.RIGHT; "largest" -> PdfImageWrap.LARGEST
                        "bothSides" -> PdfImageWrap.BOTH
                        else -> reject("Unsupported square wrapping side")
                    }
                }
                else -> reject("Unsupported image wrapping")
            }
            val allowed = setOf("simplePos", "positionH", "positionV", "extent", "effectExtent", "docPr", "cNvGraphicFramePr", wrap.name.localPart)
            val extras = anchor.elements().filterNot { it.name.namespaceURI == wp && it.name.localPart in allowed || it.name.namespaceURI == drawing && it.name.localPart == "graphic" }
            check(extras.map { it.name }.distinct().size == extras.size, "Duplicate relative image size")
            for (extra in extras) {
                source.current = extra
                WordAnchorMetadata.validate(extra, ::reject)
            }
            source.current = node
            check(!pageBand || policy == PdfImageWrap.NONE, "Only unwrapped floating images are qualified in page bands")
            val inline = XmlElement(javax.xml.namespace.QName(wp, "inline"))
            inline.children.addAll(anchor.elements().filter { it.name.namespaceURI == drawing || it.name.localPart in setOf("extent", "effectExtent", "docPr", "cNvGraphicFramePr") })
            val container = XmlElement(node.name).also { it.children += inline }
            val converted = image(container, partRelations, parts)
            check(horizontal >= 0 && horizontal + converted.width <= page.width + .001f, "Anchored image exceeds the physical page width")
            check(vertical.second != PdfBoxAnchor.PAGE || vertical.first >= 0 && vertical.first + converted.height <= page.height + .001f,
                "Anchored image exceeds the physical page height")
            return PdfAnchoredImage(converted, horizontal, vertical.first, vertical.second, policy,
                PdfMargins(number(anchor.attribute("", "distT") ?: "0") / 12700, number(anchor.attribute("", "distR") ?: "0") / 12700,
                    number(anchor.attribute("", "distB") ?: "0") / 12700, number(anchor.attribute("", "distL") ?: "0") / 12700))
        }

        fun readBlocks(root: XmlElement, partRelations: Map<String, String>, fields: Boolean): List<PdfBlock> = root.elements().filter { it.name.localPart != "sectPr" }.flatMap { block ->
            when (block.name.localPart) {
                "p" -> {
                    val frames = block.elements().filter { it.name.localPart == "r" }.flatMap { it.elements().filter { node -> node.name.namespaceURI == word && !lineReader.isLine(node) && (node.name.localPart == "pict" || node.name.localPart == "drawing" && node.elements().any { it.name.namespaceURI == wp && it.name.localPart == "anchor" }) } }
                    source.current = block
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
                    val (images, boxes) = frames.partition { node ->
                        node.elements().singleOrNull()?.elements()?.singleOrNull { it.name.namespaceURI == drawing && it.name.localPart == "graphic" }
                            ?.elements()?.singleOrNull { it.name.namespaceURI == drawing && it.name.localPart == "graphicData" }?.attribute("", "uri") == picture
                    }
                    val paragraph = readParagraph(plain, partRelations, fields)
                    boxes.map { if (it.name.localPart == "pict") readBox(it, partRelations, fields) else readDrawingBox(it, partRelations, fields) } +
                        paragraph.copy(anchoredImages = images.map { readAnchoredImage(it, partRelations, fields) })
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
            val blocks = readBlocks(bandRoot, ownRelations, true)
            source.current = margins
            activePart = provenance[margins] ?: "word/document.xml"
            val offset = twips(margins.value(name))
            val extent = (if (name == "header") page.margins.top else page.margins.bottom) - offset
            if (extent <= 0 && unprintedBand(blocks)) return null
            val available = page.height - offset - (if (name == "header") page.margins.bottom else page.margins.top) - 1f
            check(offset >= 0 && available > 0, "Page-band offset exceeds physical page geometry")
            return if (blocks.isEmpty()) null else PdfPageBand(offset, available, blocks).withContentReservation()
        }

        val headerReferences = mutableMapOf<String, XmlElement>()
        val footerReferences = mutableMapOf<String, XmlElement>()
        val sequences = ranges.mapIndexed { index, (section, content) ->
            sequenceIndex = index
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
        val notes = sourceNotes.readDefinitions { node, kind ->
            val name = NativeDocxNotes.kindName(kind) + "s.xml"
            activePart = "word/$name"
            children(node, setOf("p"))
            registerNeighbors(node)
            val ownRelations = parts["word/_rels/$name.rels"]?.let { readRelations(xml.read(it)) } ?: emptyMap()
            currentNoteKind = kind
            try { node.elements().map { readParagraph(it, ownRelations) } }
            finally { currentNoteKind = null }
        }
        check(openBookmarks.isEmpty(), "Unclosed bookmarks")
        val targets = textReferences.map { name ->
            source.current = textReferenceSources.getValue(name)
            activePart = provenance[source.current] ?: activePart
            check(name !in blockBoundaryTextRanges, "Text references to block-level bookmark ends are not qualified")
            check(name !in crossParagraphBookmarks, "Text references across paragraphs are not qualified")
            PdfReferenceTarget(name, bookmarkRuns[name] ?: reject("Unknown text reference range"))
        }
        return PdfDocument(sequences, if (sequences.size == 1) sequences.single().flow?.maxPages ?: 1 else 32, targets, notes)
    }

    private data class Face(val family: String? = null, val size: Float? = null, val bold: Boolean = false,
        val italic: Boolean = false, val color: PdfColor = PdfColor(0, 0, 0), val languageTag: String? = null, val rtl: Boolean? = null, val underline: Boolean = false, val superscript: Boolean = false, val strikethrough: Boolean = false,
        val kerningThreshold: Float = 0f, val standardLigatures: Boolean = false, val openType: Boolean = false) {

        fun id(resolve: (String, Boolean, Boolean) -> String): String = resolve(
            requireNotNull(family) { "An explicit font family is required" }, bold, italic)

    }

    private fun textStyle(face: Face): PdfTextStyle {
        val nominal = face.size ?: reject("Missing font size")
        val size = if (face.superscript) kotlin.math.round(nominal * 1.3f) / 2 else nominal
        val kern = if (face.kerningThreshold > 0 && size >= face.kerningThreshold)
            if (face.openType) PdfKerning.OPENTYPE else PdfKerning.PAIRS else PdfKerning.NONE
        return PdfTextStyle(face.id(fontId), size, face.color, face.languageTag, face.underline, if (face.superscript) nominal - size else 0f, face.strikethrough)
            .withFontFeatures(PdfFontFeatures(kern, face.standardLigatures && face.openType))
    }

    private fun runProperties(property: XmlElement?, initial: Face, themeFonts: Map<String, String>): Face {
        if (property == null) return initial
        val word14 = "http://schemas.microsoft.com/office/word/2010/wordml"
        children(property, setOf("rStyle", "rFonts", "sz", "b", "i", "color", "lang", "szCs", "bCs", "iCs", "rtl", "u", "vertAlign", "vanish", "webHidden", "strike", "kern"), setOf(word14 to "ligatures"))
        var result = initial
        property.one("kern")?.let {
            attributes(it, setOf("val")); children(it, emptySet())
            check(it.children.filterIsInstance<XmlText>().all { text -> text.text.isBlank() }, "Invalid kerning setting")
            val threshold = number(it.value("val")) / 2
            check(threshold <= 1638f, "Invalid kerning threshold")
            result = result.copy(kerningThreshold = threshold)
        }
        property.elements().singleOrNull { it.name.namespaceURI == word14 && it.name.localPart == "ligatures" }?.let { feature ->
            source.current = feature
            check(feature.elements().isEmpty() && feature.children.filterIsInstance<XmlText>().all { it.text.isBlank() } &&
                feature.attributes.keys.all { it.namespaceURI == word14 && it.localPart == "val" }, "Invalid OpenType ligature setting")
            val value = feature.attribute(word14, "val")
            check(value in setOf("none", "standard"), "Only standard OpenType ligatures are qualified")
            result = result.copy(standardLigatures = value == "standard")
        }
        property.one("vertAlign")?.let {
            attributes(it, setOf("val")); children(it, emptySet())
            check(it.value("val") in setOf("baseline", "superscript"), "Only baseline and superscript alignment are qualified")
            result = result.copy(superscript = it.value("val") == "superscript")
        }
        property.one("numPr")?.let { node ->
            attributes(node, emptySet()); children(node, setOf("numId", "ilvl")); check(node.elements().isNotEmpty(), "Empty numbering reference")
            node.elements().forEach { leaf -> attributes(leaf, setOf("val")); check(leaf.elements().isEmpty(), "Invalid numbering reference"); check(number(leaf.value("val")) <= if (leaf.name.localPart == "ilvl") 8 else 1_000_000, "Numbering reference exceeds bounds") }
        }
        property.one("rFonts")?.let {
            attributes(it, setOf("ascii", "hAnsi", "asciiTheme", "hAnsiTheme", "cs", "eastAsia"))

            fun family(name: String): String? = it.value(name + "Theme")?.let { key ->
                val kind = when (key) { "majorAscii", "majorHAnsi" -> "major"; "minorAscii", "minorHAnsi" -> "minor"; else -> reject("Unsupported theme font role") }
                themeFonts[kind] ?: reject("Unresolved theme font")
            } ?: it.value(name) ?: initial.family

            val family = family("ascii") ?: reject("Explicit Latin font required")
            check(family("hAnsi") == family, "Mixed font families are unsupported")
            check(it.value("cs") == null || it.value("cs") == family, "Separate complex-script font faces are outside this proof")
            check(it.value("eastAsia") == null || it.value("eastAsia") == family, "Separate East Asian font faces are outside this profile")
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

        // Hidden runs were removed from the private DOCX intermediary using resolved Word hidden-text formatting.
        for (name in listOf("vanish", "webHidden")) property.one(name)?.let {
            attributes(it, setOf("val")); children(it, emptySet()); enabled(it)
        }
        property.one("rtl")?.let { attributes(it, setOf("val")); check(it.elements().isEmpty(), "Invalid run direction"); result = result.copy(rtl = enabled(it)) }
        property.one("b")?.let { result = result.copy(bold = enabled(it)) }
        property.one("i")?.let { result = result.copy(italic = enabled(it)) }
        property.one("strike")?.let {
            attributes(it, setOf("val")); children(it, emptySet()); result = result.copy(strikethrough = enabled(it))
        }
        property.one("u")?.let {
            attributes(it, setOf("val")); children(it, emptySet())
            check(it.value("val") in setOf(null, "single", "none"), "Only single run underlining is qualified")
            result = result.copy(underline = it.value("val") != "none")
        }
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
        val autoBefore: Boolean = false, val autoAfter: Boolean = false,
        val mirror: Boolean = false,
        val alignment: PdfAlignment = PdfAlignment.LEFT, val left: Float = 0f, val right: Float = 0f, val first: Float = 0f,
        val keepWithNext: Boolean = false, val keepLines: Boolean = false, val widowControl: Boolean = true, val pageBreakBefore: Boolean = false, val outline: Int? = null, val listTab: Float? = null, val tabStops: List<PdfTabStop> = emptyList(), val automatic: PdfAutomaticLineSpacing? = PdfAutomaticLineSpacing(), val contextual: Boolean = false, val direction: PdfTextDirection? = null, val borders: PdfParagraphBorders? = null)

    private fun paragraphProperties(property: XmlElement, initial: Geometry): Geometry {
        children(property, setOf("pStyle", "bidi", "contextualSpacing", "spacing", "jc", "ind", "rPr", "mirrorIndents", "widowControl", "keepNext", "keepLines", "pageBreakBefore", "numPr", "outlineLvl", "tabs", "pBdr"))
        var result = initial
        property.one("pBdr")?.let { result = result.copy(borders = lineReader.borders(it, result.borders)) }

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
        property.one("mirrorIndents")?.let { check(it.elements().isEmpty(), "Invalid mirrored indentation"); result = result.copy(mirror = flag(it)) }
        property.one("spacing")?.let {
            attributes(it, setOf("before", "after", "line", "lineRule", "beforeAutospacing", "afterAutospacing"))
            check(it.elements().isEmpty() && it.children.filterIsInstance<XmlText>().all { text -> text.text.isBlank() }, "Invalid paragraph spacing")

            fun auto(name: String, inherited: Boolean): Boolean = when (it.value(name)) {
                null -> inherited; "true", "1", "on" -> true; "false", "0", "off" -> false
                else -> reject("Invalid automatic paragraph spacing")
            }

            result = result.copy(autoBefore = auto("beforeAutospacing", result.autoBefore), autoAfter = auto("afterAutospacing", result.autoAfter))
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
            result = result.copy(left = it.value("left")?.let { _ -> indentation(it, "left", true) } ?: result.left,
                right = it.value("right")?.let { _ -> indentation(it, "right", true) } ?: result.right,
                first = it.value("hanging")?.let { _ -> -indentation(it, "hanging", false) }
                    ?: it.value("firstLine")?.let { _ -> indentation(it, "firstLine", false) } ?: result.first)
        }
        return result
    }

    private fun image(node: XmlElement, relations: Map<String, String>, parts: Map<String, ByteArray>): PdfImage {
        source.current = node

        fun at(element: XmlElement, condition: Boolean, message: String) {
            source.current = element
            check(condition, message)
            source.current = node
        }

        fun imageAttributes(element: XmlElement, accepted: (javax.xml.namespace.QName, String) -> Boolean) {
            val unsupported = element.attributes.filterNot { (name, value) -> accepted(name, value) }.keys
            if (unsupported.isNotEmpty()) {
                source.current = element
                val name = unsupported.first().localPart.take(64)
                throw source.failure("Unsupported drawing attribute: ${element.name.localPart.take(64)}/@$name", activePart)
                    .also { it.unsupportedAttributes = unsupported }
            }
        }

        check(node.elements().size == 1, "Unsupported drawing")
        val inline = node.elements().single()
        check(inline.name.namespaceURI == wp && inline.name.localPart == "inline", "Only inline images are supported")
        imageAttributes(inline) { name, value -> name.namespaceURI.isEmpty() && name.localPart in setOf("distT", "distB", "distL", "distR") && value == "0" }
        WordPictureMetadata.normalize(inline) { invalid, message -> source.current = invalid; reject(message) }
        val allowed = mapOf(wp to setOf("inline", "extent", "effectExtent", "docPr", "cNvGraphicFramePr"), drawing to setOf("graphic", "graphicData", "graphicFrameLocks", "blip", "srcRect", "stretch", "fillRect", "xfrm", "off", "ext", "prstGeom", "avLst"),
            picture to setOf("pic", "nvPicPr", "cNvPr", "cNvPicPr", "blipFill", "spPr"))
        inline.descendants().firstOrNull { it.name.localPart !in (allowed[it.name.namespaceURI] ?: emptySet()) }?.let { unsupported ->
            source.current = unsupported
            val child = unsupported.elements().firstOrNull()?.name?.localPart?.take(64)
            val construct = unsupported.name.localPart.take(64) + (child?.let { "/$it" } ?: "")
            reject("Unsupported drawing element: $construct")
        }
        inline.descendants().filter { it.name.namespaceURI == drawing && it.name.localPart == "graphicFrameLocks" }.forEach { locks ->
            imageAttributes(locks) { name, value -> name.namespaceURI.isEmpty() && name.localPart == "noChangeAspect" && value in setOf("1", "true") }
            at(locks, locks.elements().isEmpty(), "Unsupported image editing lock content")
        }
        inline.elements().filter { it.name.namespaceURI == wp && it.name.localPart == "effectExtent" }.forEach { effect ->
            val unsupported = effect.attributes.entries.firstOrNull { (name, value) ->
                name.namespaceURI.isNotEmpty() || name.localPart !in setOf("l", "t", "r", "b") || value != "0"
            }
            val property = unsupported?.key?.localPart?.take(64)?.let { "@$it" } ?: "content"
            // Its four extent attributes are required together; cleanup removes the optional element, not one required attribute.
            at(effect, unsupported == null && effect.elements().isEmpty(), "Unsupported image effectExtent/$property")
        }
        val extent = inline.elements().singleOrNull { it.name.namespaceURI == wp && it.name.localPart == "extent" } ?: reject("Missing image extent")
        val blip = inline.descendants().singleOrNull { it.name.namespaceURI == drawing && it.name.localPart == "blip" } ?: reject("Missing image relationship")
        imageAttributes(blip) { name, _ -> name.namespaceURI == rel && name.localPart == "embed" }
        source.current = blip
        val target = relations[blip.attribute(rel, "embed")] ?: reject("Missing embedded image")
        at(blip, target.startsWith("media/"), "Invalid image relationship")
        val transform = inline.descendants().singleOrNull { it.name.namespaceURI == drawing && it.name.localPart == "xfrm" } ?: reject("Missing image transform")
        imageAttributes(transform) { _, _ -> false }
        val offset = transform.elements().singleOrNull { it.name.localPart == "off" } ?: reject("Missing image offset")
        at(offset, offset.attribute("", "x") == "0" && offset.attribute("", "y") == "0", "Unsupported image offset")
        val transformedExtent = transform.elements().singleOrNull { it.name.localPart == "ext" } ?: reject("Missing picture extent")
        at(transformedExtent, transformedExtent.attribute("", "cx") == extent.attribute("", "cx") && transformedExtent.attribute("", "cy") == extent.attribute("", "cy"), "Nonmatching picture transforms are unsupported")
        val shape = inline.descendants().singleOrNull { it.name.localPart == "prstGeom" } ?: reject("Missing image geometry")
        at(shape, shape.attribute("", "prst") == "rect", "Unsupported image geometry: prstGeom/@prst")
        val fill = inline.descendants().singleOrNull { it.name.namespaceURI == drawing && it.name.localPart == "fillRect" } ?: reject("Missing image stretch rectangle")
        at(fill, fill.attributes.isEmpty() && fill.elements().isEmpty(), "Inset image fill rectangles are not qualified")
        val crops = inline.descendants().filter { it.name.namespaceURI == drawing && it.name.localPart == "srcRect" }.toList()
        check(crops.size <= 1, "Duplicate image crop")
        val crop = crops.singleOrNull()
        if (crop != null) {
            imageAttributes(crop) { name, _ -> name.namespaceURI.isEmpty() && name.localPart in setOf("t", "r", "b", "l") }
            at(crop, crop.elements().isEmpty(), "Unsupported srcRect content")
        }

        fun fraction(side: String): Float {
            source.current = crop ?: node
            return number(crop?.attribute("", side) ?: "0") / 100_000
        }

        source.current = extent
        val width = number(extent.attribute("", "cx")) / 12700
        val height = number(extent.attribute("", "cy")) / 12700
        source.current = blip
        val original = parts["word/$target"] ?: reject("Missing image bytes")
        val pixels = if (target.endsWith(".emf", true)) try { metafiles.decode(original, width, height) }
        catch (error: dev.nuaforma.docx.pdf.adapters.nativeengine.emf.EmfFailure) {
            throw NativeDocxReadException(error.message ?: "Unsupported EMF image", "word/$target",
                dev.nuaforma.contracts.DocumentSourceContext((error.message ?: "Unsupported EMF image").take(256), null, "emf", ""))
        } else if (target.endsWith(".jpg", true) || target.endsWith(".jpeg", true)) jpegs.decode(original, "word/$target") else original
        return PdfImage(pixels, width, height, PdfImageCrop(fraction("t"), fraction("r"), fraction("b"), fraction("l")))
    }

    private fun unprintedBand(blocks: List<PdfBlock>): Boolean = blocks.all { block ->
        block is PdfParagraph && block.borders == null && block.lines.isEmpty() && block.anchoredImages.isEmpty() &&
            block.contents == null && block.listLabel == null && block.format.outlineLevel == null && block.runs.all { inline ->
                inline is PdfTextRun && inline.text.all { it == ' ' } && inline.navigation == null && inline.semantics == null &&
                    !inline.style.underline && !inline.style.strikethrough
            }
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
                    activePart = entry.name.take(256)
                    check(entry.name.matches(Regex("(?:\\[Content_Types]\\.xml|_rels/\\.rels|docMetadata/LabelInfo\\.xml|docProps/(?:app|core|custom)\\.xml|word/(?:(?:document|styles|numbering|settings|footnotes|endnotes|fontTable|webSettings|(?:header|footer)[1-9][0-9]?)\\.xml|theme/theme1\\.xml|_rels/(?:document|settings|footnotes|endnotes|(?:header|footer)[1-9][0-9]?)\\.xml\\.rels|media/[A-Za-z0-9_.-]+\\.(?i:png|emf|jpe?g))|customXml/(?:item(?:Props)?[1-9][0-9]?\\.xml|_rels/item[1-9][0-9]?\\.xml\\.rels))")), "Unsupported package part")
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
        for (node in root.elements()) {
            source.current = node
            val mode = node.attribute("", "TargetMode")
            val relationship = node.name.namespaceURI == relationships && node.name.localPart == "Relationship"
            if (!relationship || mode != null && !(mode == "External" && node.attribute("", "Type") == "$rel/hyperlink")) {
                throw source.failure("External or invalid relationships are unsupported", activePart).also {
                    if (relationship && mode == "Internal") it.unsupportedAttributes = setOf(javax.xml.namespace.QName("TargetMode"))
                }
            }
        }
        source.current = root
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

    private fun children(node: XmlElement, allowed: Set<String>, extensions: Set<Pair<String, String>> = emptySet()) {
        source.current = node
        activePart = provenance[node] ?: activePart
        node.elements().firstOrNull { (it.name.namespaceURI != word || it.name.localPart !in allowed) && (it.name.namespaceURI to it.name.localPart) !in extensions }?.let {
            source.current = it
            reject("Unsupported document feature")
        }
        check(node.elements().groupBy { it.name.localPart }.all { (name, values) -> name in setOf("r", "p", "style", "tbl", "tr", "tc", "tblStylePr", "fldSimple", "t", "tab", "headerReference", "footerReference", "hyperlink", "bookmarkStart", "bookmarkEnd") || values.size == 1 }, "Duplicate property")
    }

    private fun attributes(node: XmlElement, allowed: Set<String>) {
        source.current = node
        activePart = provenance[node] ?: activePart
        val unsupported = node.attributes.keys.filter { it.namespaceURI != word || it.localPart !in allowed }.toSet()
        if (unsupported.isNotEmpty()) throw source.failure("Unsupported formatting attribute", activePart).also { it.unsupportedAttributes = unsupported }
    }

    private fun XmlElement.one(name: String): XmlElement? = elements().singleOrNull { it.name.namespaceURI == word && it.name.localPart == name }

    private fun XmlElement.value(name: String) = attribute(word, name)

    private fun number(value: String?): Float = value?.toLongOrNull()?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toFloat() ?: reject("Invalid numeric property")

    private fun twips(value: String?): Float = number(value) / 20

    private fun indentation(node: XmlElement, name: String, signed: Boolean): Float {
        source.current = node
        val value = node.value(name)?.toLongOrNull()?.takeIf { it in (if (signed) -288_000L else 0L)..288_000L }
        if (value == null) throw source.failure("Invalid indentation attribute $name", activePart).also {
            it.unsupportedAttributes = setOf(javax.xml.namespace.QName(word, name))
        }
        return value.toFloat() / 20
    }

    private fun color(value: String?): PdfColor {
        check(value != null && value.matches(Regex("[0-9A-Fa-f]{6}")), "Explicit RGB color required")
        return PdfColor(value!!.substring(0, 2).toInt(16), value.substring(2, 4).toInt(16), value.substring(4, 6).toInt(16))
    }

    private fun budget(condition: Boolean) {
        if (!condition) throw dev.nuaforma.contracts.GenerationException(listOf(dev.nuaforma.contracts.Diagnostic(
            dev.nuaforma.contracts.DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, dev.nuaforma.contracts.RejectionReason.PDF_CONVERSION_BYTE_BUDGET_WAS_EXCEEDED)))
    }

    private fun check(condition: Boolean, message: String) { if (!condition) reject(message) }

    private fun reject(message: String): Nothing = throw source.failure(message, activePart)

}

internal class NativeDocxReadException(message: String, val part: String,
    sourceContext: dev.nuaforma.contracts.DocumentSourceContext? = null,
    val documentLocation: String? = null, val location: String? = null) : IllegalArgumentException(message) {
    val sourceContext: dev.nuaforma.contracts.DocumentSourceContext = sourceContext
        ?: dev.nuaforma.contracts.DocumentSourceContext(message, null, null, "")
    var sourceRoute: List<Pair<javax.xml.namespace.QName, Int>> = emptyList()
    var unsupportedAttributes: Set<javax.xml.namespace.QName> = emptySet()
}
