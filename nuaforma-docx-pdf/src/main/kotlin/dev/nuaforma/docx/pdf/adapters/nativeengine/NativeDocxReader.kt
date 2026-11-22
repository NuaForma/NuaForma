package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.contracts.Diagnostic
import dev.nuaforma.contracts.GenerationException
import dev.nuaforma.contracts.LoggingMode
import dev.nuaforma.contracts.RejectionReason
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
    private val syntheticBold: (String, Boolean, Boolean) -> Boolean,
) {

    constructor(context: GenerationContext, fontId: (String, Boolean, Boolean) -> String) : this(context, fontId, { _, _, _ -> false })

    private val xml = ConversionXml(context)
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private val relationships = "http://schemas.openxmlformats.org/package/2006/relationships"
    private val drawing = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private val wp = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    private val wp14 = "http://schemas.microsoft.com/office/word/2010/wordprocessingDrawing"
    private val picture = "http://schemas.openxmlformats.org/drawingml/2006/picture"

    private val provenance = java.util.IdentityHashMap<XmlElement, String>()
    private var activePart = "package"
    private val source = NativeDocxSource(context)
    private val parsed = linkedMapOf<String, XmlElement>()
    private val jpegs = NativeJpegImages(context)
    private val svgPictures = mutableMapOf<String, ByteArray>()
    private var svgPictureBytes = 0L
    private var metafiles = dev.nuaforma.docx.pdf.adapters.nativeengine.emf.NativeEmfImages(context, null, false)
    private val lineReader = NativeDocxLines({ node -> source.current = node; activePart = provenance[node] ?: activePart }, ::reject)
    private val vectorReader = NativeDocxVectors({ node -> source.current = node; activePart = provenance[node] ?: activePart }, ::reject)

    private fun normalizeReviewAnnotations() {
        if (WordReviewMetadata.definitions.none { it.path in parsed }) return
        for ((part, root) in parsed) WordMarkupCompatibility(context).normalize(root) { node, detail ->
            source.current = node
            source.failure(detail, part)
        }
        WordReviewAnnotations(context) { part, node, detail ->
            source.current = node
            source.failure(detail, part)
        }.normalize(parsed)
    }

    private fun parse(bytes: ByteArray, part: String): XmlElement {
        activePart = part
        return parsed.getOrPut(part) { xml.readPart(bytes, part).also { root ->
            source.index(root, part)
            source.current = root
            root.descendants().forEach { provenance[it] = part }
        } }.also { source.current = it }
    }

    fun read(bytes: ByteArray, imageFonts: ((String, Boolean, Boolean) -> java.awt.Font)? = null, inspectImages: Boolean = false,
        normalizations: MutableList<WordSourceNormalization>? = null): PdfDocument {
        metafiles = dev.nuaforma.docx.pdf.adapters.nativeengine.emf.NativeEmfImages(context, imageFonts, inspectImages)
        return try { readDocument(bytes, normalizations, inspectImages) }
        catch (error: GenerationException) { throw locatedFontFailure(error) }
        catch (error: NativeDocxReadException) { throw error }
        catch (_: IllegalArgumentException) { throw source.failure("Unsupported combination of native document formatting", activePart) }
    }

    private fun locatedFontFailure(error: GenerationException): GenerationException {
        if (context.logging.mode != LoggingMode.DEVELOPMENT || error.diagnostics.none {
                it.rejectionReason == RejectionReason.USED_FONT_FACE_UNAVAILABLE && it.sourceContext != null }) return error
        val diagnostics = error.diagnostics.map { diagnostic ->
            val detail = diagnostic.sourceContext
            if (diagnostic.rejectionReason != RejectionReason.USED_FONT_FACE_UNAVAILABLE || detail == null) diagnostic
            else {
                val location = source.failure(detail.explanation, activePart)
                Diagnostic(diagnostic.code, RejectionReason.USED_FONT_FACE_UNAVAILABLE, diagnostic.severity,
                    location.location, location.part.takeIf { it != "package" })
                    .withDocumentLocation(location.documentLocation).withSourceContext(location.sourceContext)
            }
        }
        return GenerationException(diagnostics, error)
    }

    private fun readDocument(bytes: ByteArray, normalizations: MutableList<WordSourceNormalization>?, sourceInspection: Boolean): PdfDocument {
        val parts = unpack(bytes)
        parts.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.forEach { (name, content) -> parse(content, name) }
        normalizeReviewAnnotations()
        for ((part, root) in parsed) {
            WordMarkupCompatibility(context).normalize(root) { node, message ->
                source.current = node
                source.failure(message, part)
            }
            val observe = normalizations?.let { WordSourceNormalization.observe(root, part, source) }
            NativeLineBreaks.validateSource(root, context) { node, message ->
                source.current = node
                throw source.failure(message, part)
            }
            WordGridHistoryMetadata.normalizeSource(root, context) { node, message ->
                source.current = node
                throw source.failure(message, part)
            }
            WordRunSpacing.validateSource(root) { node, message ->
                source.current = node
                throw source.failure(message, part)
            }
            WordNumberingMetadata(context).validateParagraphNumberingSource(root) { node ->
                source.current = node
                source.failure("Unsupported original Word paragraph numbering", part)
            }
            WordTableBorders.validateSource(root) { node, message ->
                source.current = node
                throw source.failure(message, part)
            }
            WordTableBorders.removeInactiveDiagonals(root)
            WordCellMargins.normalizeSource(root) { node, message ->
                source.current = node
                throw source.failure(message, part)
            }
            WordInactiveRunProperties.normalizeSource(root) { node, message ->
                source.current = node
                throw source.failure(message, part)
            }
            WordCapitalization.normalizeSource(root) { node, message ->
                source.current = node
                throw source.failure(message, part)
            }
            WordNumericSpellings.normalizeSource(root) { node, message ->
                source.current = node
                throw source.failure(message, part)
            }
            WordTableWidths.normalizeSource(root) { node, message ->
                source.current = node
                throw source.failure(message, part)
            }
            if (observe != null) normalizations.addAll(observe())
            if (sourceInspection) WordPageNumberControls.normalizeSource(root)
        }
        val conditionalMetadata = WordTableConditionalMetadata(context)
        parsed.forEach { (part, root) ->
            conditionalMetadata.inspect(root, forPrint = true) { node, message ->
                source.current = node
                throw source.failure(message, part)
            }
        }
        WordAuxiliaryParts(context) { name, node, message -> source.current = node; source.failure(message, name) }.normalize(parsed, parts.keys)
        WordThumbnailMetadata(context).normalize(parsed, parts)
        WordSensitivityMetadata(context).removeFromPrintModel(parsed)
        val propertyValues = WordPackageMetadata(context).removeFromPrintModel(parsed)
        WordCustomXmlMetadata(context).removeFromPrintModel(parsed) { name, node, message ->
            source.current = node
            source.failure(message, name)
        }
        val bibliography = WordBibliographyMetadata(context).removeFromPrintModel(parsed)
        bibliography.forEach { parsed.remove(it) }
        val automaticHyphenationSource = parsed["word/settings.xml"]?.elements()?.firstOrNull {
            it.name.namespaceURI == word && it.name.localPart == "autoHyphenation"
        }
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
        NativeDocxBookmarks(context).normalizeEditing(parsed.values, { node ->
            source.current = node; activePart = provenance[node] ?: activePart
        }, ::reject)
        val staticFields = NativeWordValues(context, propertyValues, parsed["docProps/core.xml"], { node ->
            source.current = node; activePart = provenance[node] ?: activePart
        }, ::reject)
        val propertyParagraphs = NativePropertyParagraphs(context, staticFields, { source.current = it }, { copy, original ->
            provenance[copy] = provenance[original] ?: activePart; source.alias(copy, original)
        }, ::reject)
        parsed.forEach { (name, root) -> activePart = name; propertyParagraphs.normalize(root) }
        val fieldCodeStyles = java.util.IdentityHashMap<XmlElement, XmlElement?>()
        val formFields = java.util.IdentityHashMap<XmlElement, NativeDocxFields.Form>()
        parsed.forEach { (name, root) ->
            activePart = name; source.current = root
            NativeDocxFields.normalize(root, ::reject, formFields, { field, original ->
                provenance[field] = name; source.alias(field, original)
            }, formatting = { field, properties -> fieldCodeStyles[field] = properties }) { source.current = it }
        }
        val blockBoundaryTextRanges = mutableSetOf<String>()
        parsed.forEach { (name, root) -> activePart = name; blockBoundaryTextRanges += NativeDocxBookmarks(context).normalize(root, { source.current = it }, ::reject) }
        staticFields.index(parsed.values)
        val automaticTextSpacing = WordAutomaticTextSpacing(context)
        automaticTextSpacing.normalize(parsed, { field ->
            source.current = field
            activePart = provenance[field] ?: activePart
            val instruction = NativeDocxFields.instruction(field.value("instr") ?: reject("Missing field instruction"), ::reject)
            when (instruction.kind) {
                "DOCPROPERTY" -> staticFields.propertyText(instruction)
                "SET" -> instruction.literal
                else -> null
            }
        }) { part, node, message ->
            source.current = node
            source.failure(message, part)
        }
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
        if (parts.keys.any { it.endsWith(".svg", true) }) {
            val declarations = types.elements().filter { it.attribute("", "Extension")?.equals("svg", true) == true }
            val type = declarations.singleOrNull()
            val namespace = "http://schemas.openxmlformats.org/package/2006/content-types"
            check(type?.name?.namespaceURI == namespace && type.name.localPart == "Default" &&
                type.attribute("", "ContentType") == "image/svg+xml" && type.attributes.size == 2 &&
                type.elements().isEmpty() && type.children.filterIsInstance<XmlText>().none { it.text.isNotBlank() },
                "An embedded SVG requires its standard content type")
            check(types.elements().none { it.attribute("", "PartName")?.endsWith(".svg", true) == true },
                "SVG picture content-type overrides are not qualified")
        }
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
            children(section, setOf("pgSz", "pgMar", "headerReference", "footerReference", "type", "titlePg", "pgNumType", "footnotePr", "endnotePr", "pgBorders"))
            pageNumbering(section)
            section.one("titlePg")?.let(::pageToggle)
            for (kind in listOf("headerReference", "footerReference")) {
                val types = section.elements().filter { it.name.localPart == kind }.map { it.value("type") }
                check(types.all { it in setOf("default", "first", "even") } && types.toSet().size == types.size, "Invalid or duplicate page-band type")
            }
            section.one("type")?.let { type ->
                attributes(type, setOf("val")); children(type, emptySet())
                val initialContinuous = section === ranges.first().first && type.value("val") == "continuous"
                check(type.value("val") == "nextPage" || initialContinuous, "Only next-page transitions or an initial continuous section are qualified")
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
            check(target.matches(Regex("(?:media/[A-Za-z0-9_.-]+\\.(?i:png|emf|jpe?g|svg)|styles\\.xml|numbering\\.xml|settings\\.xml|footnotes\\.xml|endnotes\\.xml|fontTable\\.xml|webSettings\\.xml|theme/theme1\\.xml|(?:header|footer)[1-9][0-9]?\\.xml)")), "Unsupported relationship target")
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
        val theme = parts["word/theme/theme1.xml"]?.let { parse(it, "word/theme/theme1.xml") }
        val themeFonts = theme?.let { theme ->
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

        val implicitFontFamily = if (theme == null) "Times New Roman" else null
        val themeColors = WordThemeColors(theme) { node, message ->
            source.current = node
            throw source.failure(message, provenance[node] ?: activePart)
        }

        fun resolveRun(property: XmlElement?, initial: Face, characterBase: Face? = null) =
            runProperties(property, initial, themeFonts, themeColors, characterBase)

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
        val normalStyle = WordParagraphStyleDefaults(context).select(styles.values) { node, message ->
            source.current = node
            activePart = "word/styles.xml"
            reject(message)
        }
        val normal = normalStyle?.value("styleId")

        val defaultTables = styles.values.filter { it.value("type") == "table" && it.value("default") in setOf("1", "true", "on") }
        check(defaultTables.size <= 1, "Multiple default table styles are not qualified")
        val defaultTable = defaultTables.singleOrNull()?.value("styleId")

        fun chain(id: String?, type: String, seen: Set<String> = emptySet()): List<XmlElement> {
            if (id == null) return emptyList()
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
        val numbering = try {
            NativeDocxNumbering(parts["word/numbering.xml"]?.let { parse(it, "word/numbering.xml") }, body, focus = { node ->
                context.checkActive(); source.current = node; activePart = provenance[node] ?: activePart
            }) { paragraph -> chain(paragraph.one("pPr")?.one("pStyle")?.value("val") ?: normal, "paragraph") }
        } catch (error: NativeDocxReadException) {
            throw error
        } catch (error: IllegalArgumentException) {
            reject("Unsupported Word numbering: ${error.message ?: "invalid numbering definition"}")
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
        val missingContentsStyles = mutableListOf<Pair<XmlElement, Set<Int>>>()
        var paragraphCounter = 0
        var fieldCounter = 0
        var linkCounter = 0
        var sequenceIndex = 0

        fun finishBookmark(node: XmlElement, tableEnding: Boolean = false) {
            source.current = node
            attributes(node, setOf("id")); children(node, emptySet())
            val key = activePart to (node.value("id") ?: reject("Missing bookmark ID"))
            check(key in openBookmarks, "Unmatched bookmark end")
            if (tableEnding) blockBoundaryTextRanges += bookmarkNames.getValue(key)
            openBookmarks.remove(key)
        }

        fun readParagraph(paragraph: XmlElement, partRelations: Map<String, String>, fields: Boolean = false, tableStyles: List<XmlElement> = emptyList(), uniformFonts: Boolean = false, allowPageBreak: Boolean = true, measureMark: Boolean = true): PdfParagraph {
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
                val resolved = paragraphProperties(property, result, themeColors, property !== direct && property in sourceProperties)
                if (legacyTableHierarchy && tableJustification && property === normalStyle?.one("pPr") &&
                    property.one("jc")?.value("val") == "left") resolved.copy(alignment = result.alignment) else resolved
            }
            val automaticBefore = geometry.autoBefore && !formatting.additiveParagraphSpacing
            val automaticAfter = geometry.autoAfter && !formatting.additiveParagraphSpacing
            geometry = geometry.copy(before = if (automaticBefore) 14f else geometry.beforeLines ?: geometry.before,
                after = if (automaticAfter) 14f else geometry.afterLines ?: geometry.after)
            if (geometry.contextual) {

                fun same(node: XmlElement?) = node != null && (node.one("pPr")?.one("pStyle")?.value("val") ?: normal) == (direct?.one("pStyle")?.value("val") ?: normal)

                val (previous, next) = neighbors[paragraph] ?: (null to null)
                geometry = geometry.copy(before = if (same(previous)) 0f else geometry.before, after = if (same(next)) 0f else geometry.after)
            }
            check(listEntry != null || geometry.listTab == null, "List tab without numbering")
            if (geometry.overflowPunctuation == true) {
                val property = paragraphChain.mapNotNull { it.one("overflowPunct") }.last()
                source.current = property
                activePart = provenance[property] ?: activePart
                reject("Active punctuation overflow is not yet qualified")
            }
            if (formatting.automaticHyphenation && !geometry.suppressAutoHyphens) {
                source.current = requireNotNull(automaticHyphenationSource)
                activePart = "word/settings.xml"
                reject("Active automatic hyphenation is not yet qualified")
            }
            val tableSize = tableStyles.any { it.one("rPr")?.one("sz") != null }
            val base = (tableStyles + styleChain).fold(resolveRun(defaultRun, Face(family = implicitFontFamily, size = 10f,
                openType = modernTableAlignment || formatting.openTypeFeatures, paragraphBackground = geometry.background))) { result, style ->
                val resolved = resolveRun(style.one("rPr"), result)
                if (legacyTableHierarchy && tableSize && style === normalStyle && style.one("rPr")?.one("sz") != null &&
                    resolved.size in setOf(11f, 12f)) resolved.copy(size = result.size) else resolved
            }

            check(!uniformFonts || !mixedFonts(base), "Mixed font selection in generated contents styles is not qualified")

            fun faceFor(properties: XmlElement?, initial: Face = base): Face {
                val characterStyle = properties?.one("rStyle")?.value("val")
                val inherited = characterStyle?.let { id ->
                    chain(id, "character").fold(initial) { acc, style ->
                        resolveRun(style.one("rPr"), acc, characterBase = initial)
                    }
                } ?: initial
                return resolveRun(properties, inherited)
            }

            val markProperties = paragraphChain.mapNotNull { it.one("rPr") }
            var markerBaseHighlight = base.highlight
            val markFace = markProperties.takeIf { it.isNotEmpty() }?.fold(base) { face, property ->
                val metrics = WordParagraphMark.metricProperties(property) { node, valid ->
                    source.current = node
                    check(valid, "Invalid paragraph-mark highlight")
                    markerBaseHighlight = WordRunBackground.highlight(node) { origin, message ->
                        source.current = origin
                        reject(message)
                    }
                }
                faceFor(metrics, face)
            }

            val pendingDestinations = mutableListOf<PdfDestination>()
            var contents: PdfContents? = null

            var emittedTextRuns = 0

            fun textRuns(run: PdfTextRun, face: Face): List<PdfTextRun> {
                val text = if (face.capitals) WordCapitalization.display(run.text, face.languageTag, ::reject) else run.text
                val usesSmallCaps = face.smallCaps && !face.capitals
                val mixed = mixedFonts(face)
                if (!usesSmallCaps && !mixed) {
                    fontRunsBound(++emittedTextRuns <= 1_000)
                    return listOf(if (text == run.text) run else run.copy(text = text))
                }
                val segments = if (!mixed) listOf(WordLatinFontSelection.Segment(text, false)) else {
                    check(face.rtl != true, "Mixed complex-script font selection is not qualified")
                    WordLatinFontSelection.segments(text, context, ::reject, ::fontRunsBound)
                }
                fun selectedStyle(segment: WordLatinFontSelection.Segment) =
                    if (segment.highAnsi) textStyle(face.copy(family = face.highAnsiFamily)) else run.style

                val visible = if (usesSmallCaps) buildList {
                    for (segment in segments) {
                        val style = selectedStyle(segment)
                        val spans = WordCapitalization.smallCaps(segment.text, face.languageTag,
                            1_000 - emittedTextRuns - size, context::checkActive, ::reject)
                        for (span in spans) {
                            val qualified = if (span.reduced) {
                                check(style.baselineShift == 0f && !style.underline && !style.strikethrough &&
                                    style.fontFeatures == PdfFontFeatures() && geometry.direction == null,
                                    "Small caps with baseline shifts, line decorations, shaping or bidi are not qualified")
                                val glyphSize = kotlin.math.round(style.size * 1.6f) / 2f
                                check(glyphSize >= 1f, "Reduced small caps exceed the qualified font size range")
                                style.copy(size = glyphSize).withLineMetricSize(style.size)
                            } else style
                            add(span.text to qualified)
                        }
                    }
                } else segments.map { it.text to selectedStyle(it) }
                fontRunsBound(emittedTextRuns + visible.size <= 1_000)
                emittedTextRuns += visible.size
                check(visible.size <= 1 || run.semantics == null, "Native text spans cannot duplicate preassigned semantic identifiers")
                return visible.mapIndexed { index, segment ->
                    val navigation = if (index == 0) run.navigation else run.navigation?.takeIf { it.link != null }?.let {
                        PdfTextNavigation(it.link, emptyList(), it.logicalId)
                    }
                    run.copy(text = segment.first, style = segment.second, navigation = navigation)
                }
            }

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
                    if (instruction.kind == "FORMTEXT") {
                        val form = formFields[run] ?: reject("Text input field requires its own form metadata")
                        NativeWordTextInput.validate(form.data, { source.current = it }, ::reject)
                        run.elements().forEach { children(it, setOf("rPr", "t")) }
                        source.current = run
                        activePart = provenance[run] ?: activePart
                        check(run.elements().any { child -> child.elements().any { it.name.localPart == "t" &&
                            it.children.filterIsInstance<XmlText>().any { text -> text.text.isNotEmpty() } } },
                            "Text input field requires a stored current display value")
                        return run.elements().flatMap { readRun(it, link, logicalLink) }
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
                        val requested = instruction.firstLevel..instruction.lastLevel
                        val levels = requested.mapNotNull { level ->
                            val candidates = styles.entries.filter { (_, style) -> style.value("type") == "paragraph" &&
                                (style.value("styleId").equals("TOC${level + 1}", true) || style.one("name")?.value("val").equals("toc ${level + 1}", true)) }
                            check(candidates.size <= 1, "Ambiguous contents paragraph style")
                            val style = candidates.singleOrNull() ?: return@mapNotNull null
                            val sample = XmlElement(paragraph.name)
                            val props = XmlElement(javax.xml.namespace.QName(word, "pPr", "w"))
                            props.children += XmlElement(javax.xml.namespace.QName(word, "pStyle", "w"), linkedMapOf(javax.xml.namespace.QName(word, "val", "w") to style.key))
                            sample.children += props
                            val textRun = XmlElement(javax.xml.namespace.QName(word, "r", "w"))
                            textRun.children += XmlElement(javax.xml.namespace.QName(word, "t", "w"), children = mutableListOf(XmlText("Contents")))
                            sample.children += textRun
                            val resolved = readParagraph(sample, partRelations, uniformFonts = true)
                            PdfContentsLevel(level, (resolved.runs.single() as PdfTextRun).style, resolved.format, resolved.spaceBefore, resolved.spaceAfter)
                        }
                        check(levels.isNotEmpty(), "Explicit contents paragraph styles are required")
                        val missing = requested.toSet() - levels.map { it.level }.toSet()
                        if (missing.isNotEmpty()) missingContentsStyles += run to missing
                        contents = PdfContents(levels, instruction.linked)
                        return emptyList()
                    }
                    val cached = run.elements()
                    val repeatedCount = fields && NativeDocxFields.isPageCount(instruction.kind)
                    val complexCount = repeatedCount && fieldCodeStyles.containsKey(run)
                    val useCode = complexCount && (!instruction.mergeFormat || cached.isEmpty())
                    check(cached.isNotEmpty() || useCode, "A field needs an explicit formatting sample")
                    cached.forEach { children(it, setOf("rPr", "t")) }
                    val cachedFace = cached.firstOrNull()?.let { faceFor(it.one("rPr")) }
                    check(useCode || cached.all { faceFor(it.one("rPr")) == cachedFace }, "Mixed field-result formatting is not qualified")
                    source.current = run
                    activePart = provenance[run] ?: activePart
                    if (instruction.charFormat) check(fieldCodeStyles.containsKey(run), "CHARFORMAT requires a qualified complex sequence code")
                    val face = if (useCode || instruction.kind == "SEQ" && !instruction.mergeFormat) faceFor(fieldCodeStyles[run])
                        else requireNotNull(cachedFace)
                    check(face.rtl != true, "Forced complex-script page fields are not qualified")
                    check(link == null || !fields && instruction.kind == "PAGEREF" &&
                        link is PdfLink.Internal && link.destination == instruction.argument,
                        "A page-reference field and its surrounding hyperlink must target the same bookmark")
                    source.current = run
                    activePart = provenance[run] ?: activePart
                    val staticValue = staticFields.resolve(instruction)
                    if (staticValue == "") return emptyList()
                    val style = textStyle(face)
                    staticValue?.let { value ->
                        check(value.codePoints().noneMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HEBREW },
                            "Hebrew static fields are outside the qualified field direction profile")
                        val navigation = pendingDestinations.takeIf { it.isNotEmpty() }?.let { PdfTextNavigation(destinations = it.toList()) }.also { pendingDestinations.clear() }
                        return textRuns(PdfTextRun(value, style, navigation), face)
                    }
                    if (fields) {
                        check(repeatedCount, "Only page counts in page bands are qualified")
                        check(complexCount || face.copy(languageTag = base.languageTag, rtl = base.rtl) == base,
                            "Page-field cache formatting must match its paragraph style")
                        check(pendingDestinations.isEmpty(), "Navigation on repeated page fields is not qualified")
                        return listOf(when (instruction.kind) { "PAGE" -> PdfPageNumber(style); "NUMPAGES" -> PdfPageCount(style); else -> PdfSequencePageCount(style) })
                    }
                    val navigation = if (link != null || pendingDestinations.isNotEmpty())
                        PdfTextNavigation(link, pendingDestinations.toList()) else null
                    pendingDestinations.clear()
                    val id = "source-field${fieldCounter++}"
                    if (instruction.kind == "SEQ") return listOf(PdfSequenceField(id, requireNotNull(instruction.argument).lowercase(java.util.Locale.ROOT), style,
                        instruction.restart, instruction.repeat, instruction.headingReset, navigation))
                    val kind = when (instruction.kind) {
                        "REF" -> PdfReferenceKind.TEXT; "PAGEREF" -> PdfReferenceKind.PAGE; "PAGE" -> PdfReferenceKind.CURRENT_PAGE
                        "NUMPAGES" -> PdfReferenceKind.TOTAL_PAGES; else -> PdfReferenceKind.SEQUENCE_PAGES
                    }
                    val target = if (kind == PdfReferenceKind.SEQUENCE_PAGES) "sequence$sequenceIndex" else instruction.argument
                    if (kind == PdfReferenceKind.TEXT) {
                        check(!mixedFonts(face), "Mixed font selection in dynamic text references is not qualified")
                        check(!face.capitals && !face.smallCaps, "Capitalization in dynamic text references is not qualified")
                        textReferences += requireNotNull(target)
                        textReferenceSources.putIfAbsent(target, run)
                    }
                    return listOf(PdfReferenceField(id, kind, style, target, instruction.linked || link != null, navigation))
                }
                children(run, setOf("rPr", "t", "tab", "br", "cr", "noBreakHyphen", "softHyphen", "drawing", "pict", "footnoteReference", "endnoteReference", "footnoteRef", "endnoteRef"))
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
                    "softHyphen" -> {
                        source.current = item
                        val style = textStyle(face)
                        check(!fields && currentNoteKind == null && geometry.direction == null && face.rtl != true &&
                            style.fontFeatures == PdfFontFeatures(), "Soft hyphens require qualified ordinary unshaped text")
                        val navigation = link?.let { PdfTextNavigation(it, emptyList(), logicalLink) }
                        PdfTextRun("\u00AD", style, navigation)
                    }
                    "t", "noBreakHyphen" -> {
                        val text = if (item.name.localPart == "noBreakHyphen") {
                            // Word paints the ordinary hyphen within the nonbreaking word.
                            "-"
                        } else {
                            check(item.elements().isEmpty(), "Invalid text node")
                            WordTextWhitespace.visible(item, item.children.filterIsInstance<XmlText>().joinToString("") { it.text })
                        }
                        if (text.isEmpty()) return@mapNotNull null
                        check('\u00AD' !in text, "Use a Word soft-hyphen control for discretionary hyphenation")
                        val hebrew = text.codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HEBREW }
                        check(face.rtl != true || hebrew, "Only Hebrew text may force complex-script reading order in this proof")
                        if (hebrew || face.rtl == true) check(geometry.direction == PdfTextDirection.RIGHT_TO_LEFT && face.rtl == true &&
                            text.codePoints().noneMatch { Character.getDirectionality(it) == Character.DIRECTIONALITY_LEFT_TO_RIGHT }, "Only explicitly marked Hebrew RTL runs are qualified")
                        if (hebrew) {
                            val explicit = run.one("rPr") ?: reject("Explicit complex-script properties are required")
                            check(explicit.one("rFonts")?.value("cs") == face.family && explicit.one("szCs")?.value("val")?.let { number(it) / 2 } == face.size &&
                                explicit.one("bCs") != null && explicit.one("iCs") != null, "Hebrew proof runs must pin all matching complex-script face properties")
                        }
                        val destinations = if (text.isNotBlank()) pendingDestinations.toList().also { pendingDestinations.clear() } else emptyList()
                        val navigation = if (link != null || destinations.isNotEmpty()) PdfTextNavigation(link, destinations, logicalLink) else null
                        PdfTextRun(text, textStyle(face), navigation)
                    }
                    "tab" -> {
                        attributes(item, emptySet())
                        check(item.elements().isEmpty(), "Invalid tab control")
                        check(link == null || !fields && currentNoteKind == null && geometry.direction == null,
                            "Linked tabs require ordinary left-to-right body text")
                        PdfTab(textStyle(face)).withNavigation(link?.let { PdfTextNavigation(it, emptyList(), logicalLink) })
                    }
                    "br", "cr" -> {
                        attributes(item, if (item.name.localPart == "br") setOf("type", "clear") else emptySet())
                        check(item.elements().isEmpty() && item.children.filterIsInstance<XmlText>().all { it.text.isBlank() }, "Invalid line break control")
                        check(item.value("type") in setOf(null, "textWrapping", "page") && item.value("clear") in setOf(null, "none"),
                            "Only ordinary text-wrapping and page breaks are qualified")
                        if (item.value("type") == "page") {
                            source.current = item
                            check(allowPageBreak && !fields && currentNoteKind == null && link == null && geometry.direction == null,
                                "Inline page breaks require ordinary body text")
                            check(!geometry.keepWithNext && !geometry.keepLines, "Inline page breaks with paragraph keep rules are not qualified")
                            PdfPageBreak(textStyle(face))
                        } else PdfLineBreak(textStyle(face))
                    }
                    "pict", "drawing" -> {
                        check(face.highlight == null && face.shading == null, "Run backgrounds on drawing objects are not qualified")
                        if (vectorReader.isVector(item)) {
                        check(link == null && pendingDestinations.isEmpty() && !face.underline && !face.strikethrough && !face.superscript,
                            "Navigation or text decorations on inline vectors are not qualified")
                        vectorReader.read(item)
                    } else if (lineReader.isLine(item)) {
                        check(link == null && pendingDestinations.isEmpty(), "Navigation on drawn lines is not qualified")
                        check(lineDrawings.size < 32, "Paragraph line budget exceeded")
                        lineDrawings += lineReader.drawing(item, page); null
                    } else {
                        check(link == null && pendingDestinations.isEmpty() && !face.underline && !face.strikethrough, "Navigation or text decorations on pictures are not qualified")
                        PdfImageRun(image(item, partRelations, parts)).withPadding(WordInlineImagePadding.inline(item) { origin, message -> source.current = origin; reject(message) })
                            .withLineClipping(true)
                    } }
                    else -> reject("Unsupported run content")
                } }.flatMap { inline -> if (inline is PdfTextRun) textRuns(inline, face) else listOf(inline) }
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
                    finishBookmark(node)
                    emptyList()
                }
                "hyperlink" -> {
                    check(node.attributes.keys.all { it.namespaceURI == rel && it.localPart == "id" || it.namespaceURI == word && it.localPart in setOf("anchor", "history") }, "Unsupported hyperlink attribute")
                    node.value("history")?.let { check(it in setOf("0", "1", "true", "false", "on", "off"), "Invalid hyperlink history") }
                    children(node, setOf("r", "fldSimple"))
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
            if (pendingDestinations.isNotEmpty()) {
                check(pendingDestinations.none { it.name == "_GoBack" }, "Bookmark requires following visible paragraph text")
                check(!fields && currentNoteKind == null && geometry.direction == null &&
                    runs.all { it is PdfTextRun || it is PdfTab || it is PdfLineBreak },
                    "Paragraph-end bookmarks require ordinary body text")
            }
            val markStyle = markFace?.takeIf { measureMark && (geometry.automatic != null || geometry.minimum != null || runs.isEmpty()) }?.let(::textStyle)
            val content = runs.ifEmpty { listOf(PdfTextRun(" ", markStyle ?: textStyle(base))) }
            check(content.none { it is PdfImageRun } || content.size == 1, "Mixed image/text paragraphs are outside the proof profile")
            val label = listEntry?.let { entry ->
                check(content.all { it is PdfTextRun || it is PdfReferenceField || it is PdfSequenceField || it is PdfLineBreak ||
                    it is PdfPageBreak || it is PdfTab || it is PdfNoteCall }, "This inline list content is outside the qualified profile")
                val markerFace = resolveRun(entry.level.one("rPr"), base.copy(highlight = markerBaseHighlight))
                val markerSegments = if (mixedFonts(markerFace)) WordLatinFontSelection.segments(entry.value.text, context, ::reject, ::fontRunsBound) else emptyList()
                check(markerSegments.size <= 1, "Mixed font selection within one list label is not qualified")
                val selected = if (markerSegments.singleOrNull()?.highAnsi == true) markerFace.copy(family = markerFace.highAnsiFamily) else markerFace
                check(!(markerFace.capitals || markerFace.smallCaps) || entry.value.text.none(Char::isLowerCase), "Capitalized list markers are not qualified")
                check(markerFace.highlight == null || entry.suffix != PdfListSuffix.SPACE || entry.alignment == PdfAlignment.LEFT,
                    "A highlighted space suffix with centered or right-aligned numbering is not qualified")
                PdfListLabel(entry.value, textStyle(selected), entry.alignment, entry.suffix, geometry.listTab, defaultTabInterval,
                    highlightSuffix = markerFace.highlight != null)
            }
            val alignment = if (geometry.direction == PdfTextDirection.RIGHT_TO_LEFT) when (geometry.alignment) {
                PdfAlignment.LEFT -> PdfAlignment.RIGHT; PdfAlignment.RIGHT -> PdfAlignment.LEFT; PdfAlignment.CENTER -> PdfAlignment.CENTER; PdfAlignment.JUSTIFY -> PdfAlignment.JUSTIFY
            } else geometry.alignment
            check(geometry.direction != PdfTextDirection.RIGHT_TO_LEFT || geometry.left == 0f && geometry.right == 0f, "RTL source indentation is not qualified")
            val pageOffset = if (geometry.mirror) {
                check(label == null && content.none { it is PdfTab } && contents == null && geometry.direction != PdfTextDirection.RIGHT_TO_LEFT,
                    "Mirrored lists, tab stops, contents and RTL indentation are not qualified")
                PdfParagraphPageOffset(geometry.right - geometry.left + maxOf(0f, -geometry.first),
                    if (!modernTableAlignment && formatting.flipLegacyMirrorIndents) PdfPageSide.ODD else PdfPageSide.EVEN)
            } else null
            if (geometry.direction == PdfTextDirection.RIGHT_TO_LEFT && (content.any { it is PdfTextRun && it.style.characterSpacing != 0f } ||
                    label?.style?.characterSpacing?.let { it != 0f } == true)) {
                source.current = paragraph
                reject("Character spacing in RTL paragraphs is not qualified")
            }
            if (contents != null && geometry.background != null) {
                source.current = direct?.one("shd") ?: paragraph
                reject("Paragraph backgrounds on generated contents are not qualified")
            }
            geometry.textAlignment?.let { node ->
                source.current = node
                activePart = provenance[node] ?: activePart
                val rendered = content.filterIsInstance<PdfTextRun>()
                val styles = rendered.map { it.style } + listOfNotNull(markStyle)
                check(geometry.line == null && geometry.direction == null && label == null && contents == null &&
                    lineDrawings.isEmpty() && (geometry.automatic?.multiple ?: 1f) in 1f..1.5f &&
                    rendered.size == content.size && rendered.all { automaticTextSpacing.supportsUniformAlignment(it.text) } &&
                    styles.all { it.baselineShift == 0f } && styles.map { it.fontId to it.size }.distinct().size <= 1,
                    "Explicit paragraph text alignment requires uniform unshifted text in natural-height lines")
            }
            return PdfParagraph(content, geometry.before, geometry.after, PdfParagraphFormat(alignment, geometry.left, geometry.right,
                geometry.first, geometry.line, !formatting.additiveParagraphSpacing,
                geometry.keepWithNext, geometry.keepLines, geometry.widowControl, geometry.pageBreakBefore, geometry.outline,
                if (geometry.tabStops.isNotEmpty() || content.any { it is PdfTab }) PdfTabSettings(geometry.tabStops, defaultTabInterval, decimalSeparator)
                    .withDecimalPolicy(PdfDecimalTabPolicy.FIRST_NUMBER, if (decimalSeparator == ",") "." else ",") else null, geometry.automatic, geometry.direction), label,
                semantics = PdfBlockSemantics(logicalId, when {
                    contents != null -> PdfSemanticRole.CONTENTS
                    geometry.outline != null -> PdfSemanticRole.HEADING
                    styleChain.any { it.value("styleId").equals("Caption", true) || it.one("name")?.value("val").equals("caption", true) } -> PdfSemanticRole.CAPTION
                    else -> PdfSemanticRole.PARAGRAPH
                }, geometry.outline), contents = contents, borders = geometry.borders, lines = lineDrawings)
                .withSpacing(PdfParagraphSpacing(automaticBefore, modernTableAlignment, automaticAfter))
                .withPageOffset(pageOffset)
                .withMinimumLineHeight(geometry.minimum)
                .withUnderlineTrailingSpaces(formatting.underlineTrailingSpaces)
                .withMinimumLineStyle(if (content.any { it is PdfLineBreak }) null else markStyle ?: if (geometry.automatic != null &&
                    (content.singleOrNull() is PdfImageRun || content.singleOrNull() is PdfVectorRun)) textStyle(base) else null)
                .withParagraphMarkStyle(if ((geometry.automatic != null || geometry.minimum != null) && content.any { it is PdfLineBreak })
                    markStyle ?: textStyle(base) else null)
                .withBackground(geometry.background)
                .withEndDestinations(pendingDestinations)
        }

        fun readAnchoredImage(node: XmlElement, partRelations: Map<String, String>, pageBand: Boolean = false,
            isolatedHeaderPicture: Boolean = false, cellRelative: Boolean = false): PdfAnchoredImage {
            source.current = node
            val anchor = node.elements().single()

            fun one(name: String) = anchor.elements().singleOrNull { it.name.namespaceURI == wp && it.name.localPart == name } ?: reject("Missing or duplicate image anchor property")

            val order = WordDrawingOrder.read(anchor) { origin, message -> source.current = origin; reject(message) }
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
                if (cellRelative) {
                    check(relative == (if (horizontal) "column" else "page") && value.name.localPart == "posOffset",
                        "Cell-relative pictures require column and page offsets")
                }
                val base = if (!cellRelative && horizontal && relative != "page") page.margins.left else 0f
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
            check(wraps.size == 1, "Unsupported image wrapping geometry")
            val wrap = wraps.single()
            source.current = wrap
            val headerContour = wrap.name.localPart == "wrapTight"
            check(headerContour || wrap.elements().isEmpty(), "Unsupported image wrapping geometry")
            val policy = when (wrap.name.localPart) {
                "wrapTight" -> {
                    check(isolatedHeaderPicture && vertical.second == PdfBoxAnchor.PAGE &&
                        order.layer == PdfDrawingLayer.BACKGROUND,
                        "Tight image wrapping requires a behind-text picture in an otherwise empty header")
                    val edge = WordHeaderWrapContour.bottom(wrap) { origin, message -> source.current = origin; reject(message) }
                    val height = number(extent.attribute("", "cy")) / 12700
                    val distance = number(anchor.attribute("", "distB") ?: "0") / 12700
                    check(vertical.first + height * edge + distance <= page.margins.top + .001f,
                        "Tight header image contour extends below the body margin")
                    PdfImageWrap.NONE
                }
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
            anchor.elements().filter { it.name.namespaceURI == wp && it.name.localPart == "effectExtent" }.forEach { effect ->
                val padding = WordInlineImagePadding.read(effect) { origin, message -> source.current = origin; reject(message) }
                source.current = effect
                if (policy != PdfImageWrap.NONE && padding != PdfMargins(0f, 0f, 0f, 0f)) {
                    val side = listOf("l", "t", "r", "b").first { effect.attribute("", it)?.toLongOrNull() != 0L }
                    reject("Unsupported anchored image effectExtent/@$side")
                }
            }
            check(!pageBand || policy == PdfImageWrap.NONE, "Only unwrapped floating images are qualified in page bands")
            check(order.layer != PdfDrawingLayer.BACKGROUND || policy == PdfImageWrap.NONE, "Wrapped background pictures are not qualified")
            val properties = anchor.elements().filter { it.name.namespaceURI == wp && it.name.localPart == "docPr" }
            val docPr = properties.singleOrNull()
            val clicks = properties.flatMap { it.elements() }.filter { it.name.namespaceURI == drawing && it.name.localPart == "hlinkClick" }
            source.current = clicks.getOrNull(1) ?: clicks.firstOrNull() ?: docPr ?: anchor
            check(clicks.isEmpty() || clicks.size == 1 && docPr != null, "Duplicate picture or hyperlink properties")
            val click = clicks.singleOrNull()
            val link = click?.let {
                val id = it.attribute(rel, "id")
                check(policy == PdfImageWrap.NONE && vertical.second == PdfBoxAnchor.PAGE && it.attributes.size == 1 &&
                    it.elements().isEmpty() && it.children.filterIsInstance<XmlText>().all { text -> text.text.isBlank() } && id != null,
                    "A picture hyperlink requires a fixed unwrapped picture and one external relationship")
                PdfLink.External(partRelations[id] ?: reject("Missing picture hyperlink relationship"))
            }
            val inline = XmlElement(javax.xml.namespace.QName(wp, "inline"))
            for (item in anchor.elements().filter { it.name.namespaceURI == drawing ||
                it.name.localPart in setOf("extent", "effectExtent", "docPr", "cNvGraphicFramePr") }) {
                val visible = if (click != null && item === docPr) XmlElement(item.name, item.attributes, item.namespaces).also {
                    it.children.addAll(item.children.filterNot { child -> child === click })
                } else item
                inline.children += visible
            }
            val container = XmlElement(node.name).also { it.children += inline }
            val converted = image(container, partRelations, parts, uniformCellPictureExtent = cellRelative)
            if (cellRelative) {
                source.current = anchor
                check(pageBand && policy == PdfImageWrap.NONE && link == null && !headerContour,
                    "Only unwrapped inert cell-relative pictures are qualified in page bands")
                return PdfAnchoredImage.inTableCell(converted, horizontal, vertical.first, order)
            }
            val pageFixed = vertical.second == PdfBoxAnchor.PAGE
            val fitsWidth = horizontal >= 0f && horizontal + converted.width <= page.width + .001f
            val fitsHeight = !pageFixed || vertical.first >= 0f && vertical.first + converted.height <= page.height + .001f
            val bounds = if (fitsWidth && fitsHeight) {
                Triple(converted, horizontal, vertical.first)
            } else {
                check(pageBand && pageFixed && policy == PdfImageWrap.NONE || fitsWidth,
                    "Anchored image exceeds the physical page width")
                check(pageBand && pageFixed && policy == PdfImageWrap.NONE || fitsHeight,
                    "Anchored image exceeds the physical page height")
                source.current = anchor
                val visible = WordPageBandPictureClip.clip(converted, horizontal, vertical.first, page)
                    ?: reject("Anchored picture has no usable area inside the page")
                Triple(visible.image, visible.left, visible.top)
            }
            return PdfAnchoredImage(bounds.first, bounds.second, bounds.third, vertical.second, policy,
                PdfMargins(number(anchor.attribute("", "distT") ?: "0") / 12700, number(anchor.attribute("", "distR") ?: "0") / 12700,
                    number(anchor.attribute("", "distB") ?: "0") / 12700, number(anchor.attribute("", "distL") ?: "0") / 12700)).withDrawingOrder(order).withLink(link)
        }

        fun readBandCellParagraph(paragraph: XmlElement, partRelations: Map<String, String>, cellStyles: List<XmlElement>,
                                  allowCellPictures: Boolean): PdfParagraph {
            if (!allowCellPictures) return readParagraph(paragraph, partRelations, true, cellStyles, allowPageBreak = false)
            val runs = paragraph.elements().filter { run ->
                run.name.namespaceURI == word && run.name.localPart == "r" && run.elements().any { element ->
                    element.name.namespaceURI == word && element.name.localPart == "drawing" &&
                        element.elements().singleOrNull()?.let { it.name.namespaceURI == wp && it.name.localPart == "anchor" } == true
                }
            }
            if (runs.isEmpty()) return readParagraph(paragraph, partRelations, true, cellStyles, allowPageBreak = false)
            val drawings = runs.map { run ->
                source.current = run
                check(run.elements().size in 1..2 && run.elements().all {
                    it.name.namespaceURI == word && it.name.localPart in setOf("rPr", "drawing")
                }, "Mixed picture and text runs in page-band cells are not qualified")
                run.one("drawing") ?: reject("Missing cell picture")
            }
            val plain = XmlElement(paragraph.name, paragraph.attributes, paragraph.namespaces).also {
                it.children.addAll(paragraph.children.filterNot { child -> child in runs })
            }
            numbering.alias(paragraph, plain)
            neighbors[plain] = neighbors[paragraph] ?: (null to null)
            val text = readParagraph(plain, partRelations, true, cellStyles, allowPageBreak = false)
            return text.copy(anchoredImages = drawings.map {
                readAnchoredImage(it, partRelations, pageBand = true, cellRelative = true)
            })
        }

        fun readTable(table: XmlElement, partRelations: Map<String, String>, fields: Boolean, depth: Int = 0): PdfTable {
            source.current = table
            check(depth <= 1, "Only one nested table level is supported")
            children(table, setOf("tblPr", "tblGrid", "tr", "bookmarkEnd"))
            val properties = table.one("tblPr") ?: reject("Explicit table properties required")
            children(properties, setOf("tblStyle", "tblW", "tblInd", "jc", "tblLayout", "tblBorders", "tblCellMar", "tblLook",
                "tblStyleRowBandSize", "tblStyleColBandSize", "shd", "tblpPr", "tblOverlap"))
            val position = properties.one("tblpPr")
            val overlap = properties.one("tblOverlap")
            if (position == null && overlap != null) {
                source.current = overlap
                reject("A table overlap rule requires a qualified position")
            }
            if (position != null) {
                source.current = position
                attributes(position, setOf("leftFromText", "rightFromText", "vertAnchor", "tblpY"))
                children(position, emptySet())
                val rule = overlap ?: reject("A full-width text-relative table needs a nonoverlap rule")
                attributes(rule, setOf("val"))
                children(rule, emptySet())
                source.current = position
                check(position.value("leftFromText") == "180" && position.value("rightFromText") == "180" &&
                    position.value("vertAnchor") == "text" && position.value("tblpY") == "1" &&
                    rule.value("val") == "never", "Only the qualified nonoverlapping text-relative table position is supported")
            }
            // Word paints table shading in inter-cell spacing; this profile admits only contiguous cells.
            properties.one("shd")?.let {
                WordThemeColors.validateTableShading(it) { origin, message -> source.current = origin; reject(message) }
            }
            val tableStyleId = properties.one("tblStyle")?.let { it.value("val") ?: reject("Missing table style") } ?: defaultTable
            val tableStyles = tableStyleId?.let { chain(it, "table") } ?: emptyList()
            val rowNodes = table.elements().filter { it.name.localPart == "tr" }
            val headerCount = rowNodes.takeWhile { row -> row.one("trPr")?.one("tblHeader")?.let { it.value("val") in setOf(null, "1", "true", "on") } == true }.size
            val regions = NativeDocxTableStyles(tableStyles, properties, rowNodes.size,
                table.one("tblGrid")?.elements()?.size ?: 0, headerCount, context) { node, message -> source.current = node; reject(message) }
            val alignment = regions.property("jc")?.let { property ->
                WordTableAlignment.read(property) { origin, message -> source.current = origin; reject(message) }
            } ?: PdfTableAlignment.LEFT
            val layout = regions.property("tblLayout")?.value("type")
            check(layout in setOf(null, "fixed", "autofit"), "Unsupported table layout")
            val automaticLayout = layout != "fixed"
            val naturalWidths = layout == "autofit" && regions.property("tblW")?.let {
                it.value("type") == "auto" && it.value("w") == "0"
            } == true && rowNodes.flatMap { it.elements().filter { cell -> cell.name.localPart == "tc" } }.all { cell ->
                cell.one("tcPr")?.one("tcW")?.let { it.value("type") == "auto" && it.value("w") == "0" } == true
            }
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

            fun preferredWidth(node: XmlElement?): PdfPreferredWidth? = node?.let {
                WordTableWidths.preferred(it) { origin, message -> source.current = origin; reject(message) }
            }

            val tableWidth = regions.property("tblW")
            val preferredTableWidth = if (automaticLayout) preferredWidth(tableWidth) else null
            if (naturalWidths) automaticDimension(requireNotNull(tableWidth))
            else if (!automaticLayout) {
                val fixedWidth = tableWidth ?: reject("Missing table width")
                if (fixedWidth.value("type") == "auto") automaticDimension(fixedWidth)
                else check(kotlin.math.abs(widths.sum() - dimension(fixedWidth)) < 0.01f, "Table width differs from grid")
            }
            val cellMargins = regions.property("tblCellMar")
            cellMargins?.let { children(it, setOf("top", "right", "bottom", "left")) }

            fun edge(name: String): Float = dimension(regions.margin(name) ?: reject("Missing cell margin"))
            val borderNames = listOf("top", "left", "bottom", "right", "insideH", "insideV")
            val borderLayers = regions.borderLayers()
            val declarations = linkedMapOf<String, XmlElement>()
            for (layer in borderLayers) {
                children(layer, borderNames.toSet())
                layer.elements().forEach { declarations[it.name.localPart] = it }
            }
            val tableBorders = declarations.mapValues { (_, node) ->
                WordTableBorders.read(node, themeColors) { origin, message -> source.current = origin; reject(message) }
            }
            val borderValues = borderNames.map(tableBorders::get).distinct()
            val sparseBorders = borderValues.size > 1
            val bottomOnly = sparseBorders && tableBorders["bottom"] != null &&
                tableBorders.entries.none { (side, stroke) -> side != "bottom" && stroke != null }
            val bandCellWidth = rowNodes.singleOrNull()?.elements()?.filter { it.name.localPart == "tc" }
                ?.singleOrNull()?.one("tcPr")?.one("tcW")
            val singleBandRule = fields && modernTableAlignment && depth == 0 && widths.size == 1 &&
                !regions.active && position == null && alignment == PdfTableAlignment.LEFT &&
                tableBorders["top"] != null && tableBorders.none { (side, stroke) -> side != "top" && stroke != null } &&
                bandCellWidth?.value("type") == "dxa" && bandCellWidth.value("w") == grid.elements().single().value("w") &&
                (tableWidth?.value("type") == "dxa" && !automaticLayout ||
                    tableWidth?.value("type") == "auto" && tableWidth.value("w") == "0")
            if (sparseBorders) {
                source.current = borderLayers.last()
                val ordinaryBodyEdges = borderValues.filterNotNull().size == 1 && modernTableAlignment && depth == 0 &&
                    !fields && cellMargins != null && !naturalWidths && preferredTableWidth?.percent == null &&
                    alignment == PdfTableAlignment.LEFT
                check(ordinaryBodyEdges || singleBandRule,
                    "Table edges with independent visibility require a flat modern body table or a single qualified page-band rule")
            }
            // Direct absent edges on every ordinary cell suppress the entire inherited grid.
            val suppressGrid = borderValues.singleOrNull() != null && cellMargins == null && modernTableAlignment &&
                depth == 0 && !fields && !regions.active && properties.one("tblpPr") == null &&
                regions.property("jc")?.value("val") in setOf(null, "left") &&
                WordTableBorders.allCellsSuppressGrid(rowNodes, context)
            val border = if (sparseBorders || suppressGrid) null else borderValues.single()
            val padding = if (cellMargins == null) {
                source.current = properties
                check(modernTableAlignment && depth == 0 && !fields || singleBandRule, "Explicit cell margins required for this table")
                val clearance = if (singleBandRule) 5.4f else maxOf(.5f, (border?.width ?: 0f) / 2f)
                PdfMargins(0f, clearance, 0f, clearance)
            } else PdfMargins(edge("top"), edge("right"), edge("bottom"), edge("left"))
            val resolvedTableWidth = preferredTableWidth?.let { preference ->
                val percent = preference.percent
                if (percent != null && depth == 0) {
                    val textWidth = page.width - page.margins.left - page.margins.right
                    val reference = if (modernTableAlignment) textWidth - (border?.width ?: 0f)
                        else textWidth + padding.left + padding.right
                    val preferred = reference * percent / 100f
                    check(preferred in 0f..14_400f, "Preferred table width exceeds the physical layout limit")
                    PdfPreferredWidth.points(preferred)
                } else preference
            }

            fun ranked(value: PdfBorder): PdfEdgeStroke {
                val units = value.width * 8
                check(units == units.toInt().toFloat(), "Cell-border fallback must use integral eighth points")
                val c = value.color
                val priority = (units.toLong() shl 32) or ((1020 - c.red - c.blue - 2 * c.green).toLong() shl 22) or
                    ((765 - c.blue - 2 * c.green).toLong() shl 12) or (255 - c.green).toLong()
                return PdfEdgeStroke(value, priority)
            }

            val sparseStrokes by lazy { tableBorders.mapValues { (_, border) -> border?.let(::ranked) } }

            fun tableCellEdges(rowIndex: Int, column: Int): PdfCellBorders = PdfCellBorders(
                sparseStrokes[if (rowIndex == 0) "top" else "insideH"],
                sparseStrokes[if (column == widths.lastIndex) "right" else "insideV"],
                sparseStrokes[if (rowIndex == rowNodes.lastIndex) "bottom" else "insideH"],
                sparseStrokes[if (column == 0) "left" else "insideV"],
            )

            val fallbackStroke by lazy { border?.let(::ranked) }
            val fallbackBorders by lazy { PdfCellBorders(fallbackStroke, fallbackStroke, fallbackStroke, fallbackStroke) }

            fun readCellBorders(node: XmlElement, inherited: PdfCellBorders = fallbackBorders): PdfCellBorders {
                attributes(node, emptySet()); children(node, setOf("top", "right", "bottom", "left"))

                fun stroke(name: String): PdfEdgeStroke? {
                    val edge = node.one(name) ?: return when (name) { "top" -> inherited.top; "right" -> inherited.right; "bottom" -> inherited.bottom; else -> inherited.left }
                    val stroke = WordTableBorders.read(edge, themeColors) { origin, message -> source.current = origin; reject(message) }
                    return if (edge.value("val") == "none") fallbackStroke else stroke?.let(::ranked)
                }

                return PdfCellBorders(stroke("top"), stroke("right"), stroke("bottom"), stroke("left"))
            }

            val headerFlags = mutableListOf<Boolean>()
            val rowMinimums = mutableListOf<Float?>()
            val rowPaddingFrames = mutableListOf<Float>()
            val omissions = mutableListOf<Pair<Int, Int>>()
            var tableWidthFloor: Float? = null
            data class CellSpec(
                val blocks: List<PdfBlock>, val background: PdfColor?, val span: Int, val alignment: PdfVerticalAlignment,
                val borders: PdfCellBorders?, val preferredWidth: PdfPreferredWidth?, val protectPreferredWidth: Boolean,
                val declaredMargins: PdfMargins?, val omittedEndMark: XmlElement?,
                var margins: PdfMargins? = declaredMargins, var rows: Int = 1,
            )
            val specs = mutableListOf<Pair<List<CellSpec>, Boolean>>()
            var previous = mapOf<Int, CellSpec>()
            val events = table.elements().iterator()

            fun bookmarksBefore(row: XmlElement? = null) {
                for (event in events) {
                    if (event === row) return
                    if (event.name.localPart == "bookmarkEnd") finishBookmark(event, tableEnding = true)
                }
            }

            for ((rowIndex, row) in rowNodes.withIndex()) {
                bookmarksBefore(row)
                source.current = row
                children(row, setOf("trPr", "tc"))
                val rowProperties = row.one("trPr")
                rowProperties?.let { children(it, setOf("cantSplit", "tblHeader", "trHeight", "jc",
                    "gridBefore", "gridAfter", "wBefore", "wAfter")) }
                val (leading, trailing) = WordTableOmittedGrid.read(rowProperties, widths) { origin, message ->
                    source.current = origin
                    reject(message)
                }
                if (leading + trailing > 0) {
                    source.current = rowProperties ?: row
                    check(!automaticLayout && !sparseBorders && depth == 0,
                        "Omitted Word columns require a top-level fixed table with uniform borders")
                }
                omissions += leading to trailing
                val lastColumn = widths.size - trailing
                rowProperties?.one("jc")?.let { property ->
                    val rowAlignment = WordTableAlignment.read(property) { origin, message -> source.current = origin; reject(message) }
                    source.current = property
                    check(rowAlignment == alignment, "Table row alignment differs from the effective table alignment")
                }
                rowMinimums += rowProperties?.one("trHeight")?.let { node ->
                    WordRowHeights.read(node) { origin, message -> source.current = origin; reject(message) }
                }

                fun enabled(node: XmlElement): Boolean = when (node.value("val")) { null, "1", "true" -> true; "0", "false" -> false; else -> reject("Invalid row toggle") }

                val allowSplit = rowProperties?.one("cantSplit")?.let { !enabled(it) } ?: true
                headerFlags += rowProperties?.one("tblHeader")?.let(::enabled) == true
                val cells = mutableListOf<CellSpec>()
                val marginInputs = mutableListOf<PdfMargins>()
                val active = mutableMapOf<Int, CellSpec>()
                var column = leading
                for (cell in row.elements().filter { it.name.localPart == "tc" }) {
                    children(cell, setOf("tcPr", "p", "tbl"))
                    val cp = cell.one("tcPr") ?: reject("Explicit cell properties required")
                    children(cp, setOf("tcW", "shd", "gridSpan", "vMerge", "vAlign", "tcBorders", "tcMar", "noWrap", "hideMark"))
                    val span = cp.one("gridSpan")?.let { attributes(it, setOf("val")); number(it.value("val")).toInt() } ?: 1
                    check(span in 1..16 && column + span <= lastColumn, "Invalid grid span")
                    check(!regions.active || cp.one("vMerge") == null && (span == 1 || bottomOnly && regions.supportsHorizontalSpans()),
                        "Merged conditional table regions are not qualified")
                    val cellStyles = regions.layers(rowIndex, column, span)
                    val cellProperties = cellStyles.mapNotNull { it.one("tcPr") } + cp
                    val marginDeclarations = cellProperties.mapNotNull { it.one("tcMar") }
                    val effectiveMargins = WordCellMargins.resolve(marginDeclarations, padding) { origin, message ->
                        source.current = origin
                        reject(message)
                    }
                    marginInputs += effectiveMargins
                    val edgePolicies = cellProperties.mapNotNull { it.one("tcBorders") }
                    val declaredCellBorders = if (edgePolicies.isEmpty()) null else edgePolicies.fold(fallbackBorders) { inherited, node -> readCellBorders(node, inherited) }
                    val cellBorders = if (sparseBorders) {
                        source.current = cell
                        val horizontalEdges = bottomOnly && edgePolicies.all { policy ->
                            policy.elements().all { edge -> edge.name.namespaceURI == word &&
                                edge.name.localPart in setOf("top", "bottom") && edge.value("val") == "single" }
                        } && listOfNotNull(declaredCellBorders?.top, declaredCellBorders?.bottom).all {
                            it.border.width == tableBorders["bottom"]?.width
                        }
                        check(cp.one("vMerge") == null && cell.elements().none { it.name.localPart == "tbl" } &&
                            (horizontalEdges || cp.one("gridSpan") == null && declaredCellBorders == null),
                            "Independent table edges with merged, nested or custom-bordered cells are not qualified")
                        val inherited = tableCellEdges(rowIndex, column)
                        if (horizontalEdges && declaredCellBorders != null) inherited.copy(
                            top = declaredCellBorders.top ?: inherited.top, bottom = declaredCellBorders.bottom ?: inherited.bottom,
                        ) else inherited
                    } else declaredCellBorders?.takeUnless {
                        cellMargins == null && it == fallbackBorders && (suppressGrid || border == null ||
                            edgePolicies.none { policy -> policy.elements().any { it.value("val") == "none" } })
                    }
                    // Explicit horizontal padding cannot place text inside a visible border's inner half.
                    val leftInset = (cellBorders?.left?.border?.width ?: if (cellBorders == null) border?.width ?: 0f else 0f) / 2
                    val rightInset = (cellBorders?.right?.border?.width ?: if (cellBorders == null) border?.width ?: 0f else 0f) / 2
                    val physicalMargins = effectiveMargins.copy(
                        left = if (marginDeclarations.any { it.one("left") != null }) maxOf(effectiveMargins.left, leftInset) else effectiveMargins.left,
                        right = if (marginDeclarations.any { it.one("right") != null }) maxOf(effectiveMargins.right, rightInset) else effectiveMargins.right)
                    val ownMargins = physicalMargins.takeUnless { it == padding }
                    val alignment = cellProperties.mapNotNull { it.one("vAlign") }.lastOrNull()?.let { node ->
                        attributes(node, setOf("val")); children(node, emptySet())
                        when (node.value("val")) { "top" -> PdfVerticalAlignment.TOP; "center" -> PdfVerticalAlignment.CENTER; "bottom" -> PdfVerticalAlignment.BOTTOM; else -> reject("Unsupported cell vertical alignment") }
                    } ?: PdfVerticalAlignment.TOP
                    val cellWidth = cp.one("tcW")
                    var preferredCellWidth = if (automaticLayout) preferredWidth(cellWidth) else null
                    val protectPreferredWidth = cp.one("noWrap")?.let { node ->
                        val enabled = WordTableWidths.cellToggle(node) { origin, message -> source.current = origin; reject(message) }
                        if (!enabled || !automaticLayout) return@let false
                        source.current = node
                        val physical = preferredCellWidth?.points
                        check(depth == 0 && cp.one("vMerge") == null && physical != null && physical > 0f,
                            "Cell no-wrap in automatic tables requires a positive physical width and a top-level unmerged row")
                        if (span == 1) true else {
                            check(span == widths.size && (preferredTableWidth?.points ?: 0f) > 0f,
                                "Spanning cell no-wrap requires the whole grid of a physically preferred table")
                            tableWidthFloor = maxOf(tableWidthFloor ?: 0f, requireNotNull(physical))
                            // The span protects the table as a whole; do not distribute its preference again.
                            preferredCellWidth = null
                            false
                        }
                    } ?: false
                    if (naturalWidths) automaticDimension(cellWidth ?: reject("Missing cell width"))
                    else if (!automaticLayout) {
                        val preferred = dimension(cellWidth ?: reject("Missing cell width"))
                        val declared = widths.subList(column, column + span).sum()
                        val difference = declared - preferred
                        // Word retains the fixed outer edge when the last cell's preferred width is slightly smaller.
                        val roundedFinalCell = depth == 0 && !fields && trailing == 0 &&
                            tableWidth?.value("type") == "dxa" && column + span == widths.size &&
                            difference > 0f && difference <= 3f / 20f + .001f
                        check(kotlin.math.abs(difference) < .01f || roundedFinalCell, "Inconsistent cell width")
                    }
                    val background = cellProperties.mapNotNull { it.one("shd") }.lastOrNull()?.let(themeColors::cellFill)
                    val hideMark = cp.one("hideMark")?.takeIf { node ->
                        WordTableWidths.cellToggle(node) { origin, message -> source.current = origin; reject(message) }
                    }
                    val allowCellPictures = fields && depth == 0 && rowNodes.size == 1 && column == 0 &&
                        span == 1 && cp.one("vMerge") == null && alignment == PdfVerticalAlignment.TOP
                    val blocks = cell.elements().filter { it.name.localPart != "tcPr" }.map { child ->
                        when (child.name.localPart) {
                            "p" -> (if (fields) readBandCellParagraph(child, partRelations, cellStyles, allowCellPictures)
                                else readParagraph(child, partRelations, false, cellStyles, allowPageBreak = false)).let { paragraph ->
                                val pageBandWrapping = fields && cp.one("noWrap") == null && paragraph.format.textDirection == null
                                val resolved = paragraph.withLongWordBreaking(depth == 0 && !automaticLayout && (!fields || pageBandWrapping))
                                if (depth == 0 && !fields && span == 1 && cp.one("vMerge") == null)
                                    resolved.copy(format = resolved.format.copy(keepLines = false, widowControl = false))
                                else resolved
                            }
                            "tbl" -> readTable(child, partRelations, fields, depth + 1)
                            else -> reject("Unsupported cell block")
                        }
                    }.let { content ->
                        // Word's required empty cell-end paragraph after a nested table is a terminator,
                        // rather than another visible exact-height text line in this trial profile.
                        val last = content.lastOrNull()
                        if (content.size >= 2 && content[content.lastIndex - 1] is PdfTable && last is PdfParagraph &&
                            last.runs.all { it is PdfTextRun && it.text.isBlank() }) content.dropLast(1) else content
                    }
                    val merge = cp.one("vMerge")?.let { attributes(it, setOf("val")); it.value("val") ?: "continue" }
                    var omittedEndMark: XmlElement? = null
                    val printable = if (hideMark != null) {
                        source.current = hideMark
                        check(depth == 0 && merge == null, "Omitting cell end marks in nested or vertically merged cells is not qualified")
                        val end = blocks.lastOrNull() as? PdfParagraph ?: reject("Cell end marks require a final paragraph")
                        val visible = end.runs.any { it is PdfTextRun && it.text.isNotBlank() }
                        val terminalBreak = end.runs.last() is PdfLineBreak
                        check(end.listLabel == null && end.contents == null && end.runs.all {
                            it is PdfTextRun || it is PdfLineBreak || it is PdfTab && visible && it.navigation == null
                        } && (!terminalBreak || visible && end.endDestinations.isEmpty()),
                            "Omitting the end mark of this cell paragraph is not qualified")
                        if (end.runs.all { it is PdfTextRun && it.text.all { c -> c == ' ' } }) omittedEndMark = hideMark
                        // An explicit final soft break creates an empty line for the paragraph mark.
                        // An omitted mark removes that last line while earlier explicit breaks remain.
                        val content = if (terminalBreak) end.copy(runs = end.runs.dropLast(1)) else end
                        blocks.dropLast(1) + content.withMinimumLineStyle(null).withParagraphMarkStyle(null)
                    } else blocks
                    when (merge) {
                        "continue" -> {
                            val origin = previous[column] ?: reject("Vertical continuation has no origin")
                            check(origin.span == span && origin.background == background, "Inconsistent vertical continuation")
                            check(cp.one("vAlign") == null || origin.alignment == alignment, "Inconsistent vertical continuation alignment")
                            check(cellBorders == null || origin.borders == cellBorders, "Inconsistent vertical continuation borders")
                            check(origin.declaredMargins == ownMargins, "Inconsistent vertical continuation margins")
                            check(blocks.all { it is PdfParagraph && it.runs.all { run -> run is PdfTextRun && run.text.isBlank() } }, "Continuation cells must not contain additional content")
                            origin.rows++
                            active[column] = origin
                        }
                        null, "restart" -> {
                            val spec = CellSpec(printable, background, span, alignment, cellBorders, preferredCellWidth, protectPreferredWidth, ownMargins, omittedEndMark)
                            cells += spec
                            if (merge == "restart") active[column] = spec
                        }
                        else -> reject("Invalid vertical merge")
                    }
                    column += span
                }
                check(column == lastColumn, "Incomplete table row")
                // Word shares the greatest vertical padding across the row; the neutral model stays physical.
                val topPadding = marginInputs.maxOf { it.top }
                val bottomPadding = marginInputs.maxOf { it.bottom }
                rowPaddingFrames += topPadding + bottomPadding
                for (cell in cells) {
                    cell.margins = (cell.declaredMargins ?: padding).copy(top = topPadding, bottom = bottomPadding).takeUnless { it == padding }
                }
                for (cell in active.values.distinct().filter { it !in cells }) {
                    cell.margins = (cell.margins ?: padding).copy(bottom = bottomPadding).takeUnless { it == padding }
                }
                specs += cells to allowSplit
                previous = active
            }
            bookmarksBefore()
            val customBorders = specs.any { (cells, _) -> cells.any { it.borders != null } }
            if (cellMargins == null && customBorders) {
                source.current = properties
                reject("Explicit cell margins required for tables with cell borders")
            }
            // Word moves these rows as units. The PDF engine does not fragment vertical merges.
            val hasVerticalMerges = specs.any { (cells, _) -> cells.any { it.rows > 1 } }
            val rows = specs.mapIndexed { rowIndex, (cells, allow) ->
                val resolved = cells.map { cell ->
                    val resolvedCell = PdfTableCell(cell.blocks, cell.background, cell.span, cell.rows, cell.alignment,
                        if (customBorders) cell.borders ?: fallbackBorders else null, cell.preferredWidth, cell.margins)
                        .withProtectedPreferredWidth(cell.protectPreferredWidth)
                    val endMark = cell.omittedEndMark ?: return@map resolvedCell
                    try {
                        resolvedCell.withOmittedTrailingEmptyParagraph(true)
                    } catch (invalid: IllegalArgumentException) {
                        source.current = endMark
                        reject("Omitting a decorated or spaced cell end mark is not qualified")
                    }
                }
                val minimum = rowMinimums[rowIndex]?.let { contentFloor ->
                    val topBorder = maxOf(border?.width ?: 0f, resolved.maxOfOrNull { it.borders?.top?.border?.width ?: 0f } ?: 0f)
                    val physical = contentFloor + rowPaddingFrames[rowIndex] + topBorder
                    source.current = rowNodes[rowIndex].one("trPr")?.one("trHeight") ?: rowNodes[rowIndex]
                    check(physical <= 14_400f, "Minimum row height exceeds the physical layout limit")
                    physical
                }
                val (leading, trailing) = omissions[rowIndex]
                PdfTableRow(resolved, allow && !hasVerticalMerges, splitOversized = true, minimumHeight = minimum)
                    .withOmittedColumns(leading, trailing)
            }
            if (customBorders) {
                val styles = rows.flatMap { it.cells }.flatMap { cell -> cell.borders!!.let { listOfNotNull(it.top, it.right, it.bottom, it.left) } }.map { it.border }.distinct()
                // The shared border resolver can choose a winner for competing collinear strokes.
                val fixedRowEdges = depth == 0 && !fields && !automaticLayout && !naturalWidths &&
                    rows.all { row -> row.cells.all { cell ->
                        cell.columnSpan == 1 && cell.rowSpan == 1 && cell.blocks.none { it is PdfTable }
                    } }
                val uniformWidths = bottomOnly && styles.all { it.width == tableBorders["bottom"]?.width }
                check(styles.size <= 1 || uniformWidths || fixedRowEdges && styles.map { it.color }.distinct().size == 1,
                    "Mixed cell-border widths require a fixed flat body table with one solid color")
            }
            val count = headerFlags.takeWhile { it }.size
            check(headerFlags.drop(count).none { it }, "Table headers must be leading rows")
            source.current = regions.property("jc") ?: table
            check(depth == 0 || alignment == PdfTableAlignment.LEFT, "Aligned nested Word tables are not yet qualified")
            check(modernTableAlignment || alignment == PdfTableAlignment.LEFT || preferredTableWidth?.percent == null,
                "Legacy aligned percentage tables are not yet qualified")
            check(modernTableAlignment || alignment != PdfTableAlignment.RIGHT || !customBorders,
                "Legacy right-aligned tables with custom cell borders are not yet qualified")
            val declaredIndent = regions.indentation()
            val indent = declaredIndent?.let { node ->
                WordNumericSpellings.tableIndentPoints(node) { origin, message -> source.current = origin; reject(message) }
            } ?: 0f
            // Word clamps negative indentation in nested tables without changing the source property.
            val effectiveIndent = if (depth > 0) indent.coerceAtLeast(0f) else indent
            val halfBorder = ((if (sparseBorders) tableBorders["left"] else border)?.width ?: 0f) / 2
            val physicalIndent = when (alignment) {
                PdfTableAlignment.CENTER -> 0f
                PdfTableAlignment.RIGHT -> if (depth == 0 && !modernTableAlignment) padding.right + halfBorder else 0f
                PdfTableAlignment.LEFT -> if (depth == 0 && !modernTableAlignment) {
                    // Legacy Word distinguishes an omitted indent from an explicitly declared zero.
                    if (declaredIndent == null) 0f else indent - padding.left
                } else effectiveIndent + halfBorder
            }
            val legacyOverflow = if (depth == 0 && !modernTableAlignment) when {
                alignment == PdfTableAlignment.RIGHT -> padding.right + halfBorder
                alignment == PdfTableAlignment.LEFT && declaredIndent != null && preferredTableWidth?.percent != null -> padding.right
                else -> 0f
            } else 0f
            val contentWidth = page.width - page.margins.left - page.margins.right
            if (position != null) {
                source.current = position
                check(depth == 0 && !fields && ranges.size == 1 && table in body.elements() &&
                    modernTableAlignment && !automaticLayout && alignment == PdfTableAlignment.LEFT && indent == 0f &&
                    contentWidth - widths.sum() in 0f..0.25f,
                    "Only a full-width fixed table in ordinary body flow can use the qualified text-relative anchor")
                val floating = body.descendants().filter { it.name.namespaceURI == word && it.name.localPart == "tblpPr" }
                val otherObjects = body.descendants().any { it.name.namespaceURI == wp && it.name.localPart == "anchor" ||
                    it.name.namespaceURI == word && it.name.localPart == "pict" }
                check(floating.count() == 1 && !otherObjects, "Overlapping body objects with text-relative tables are not qualified")
            }
            val fixedOffset = when (alignment) {
                PdfTableAlignment.LEFT -> physicalIndent
                PdfTableAlignment.CENTER -> (contentWidth - widths.sum()) / 2 + physicalIndent
                PdfTableAlignment.RIGHT -> contentWidth - widths.sum() - halfBorder + physicalIndent
            }
            val fixedPageBandWidth = fields && modernTableAlignment && alignment == PdfTableAlignment.LEFT
            // A fixed page-band table may enter the right margin. Physical page bounds still apply.
            val fixedOverflow = if (depth == 0 && !automaticLayout && (!fields || fixedPageBandWidth))
                (fixedOffset + widths.sum() - contentWidth).coerceAtLeast(0f) else 0f
            val finalTableWidth = tableWidthFloor?.let { minimum ->
                PdfPreferredWidth.points(maxOf(minimum, requireNotNull(resolvedTableWidth?.points)))
            } ?: resolvedTableWidth
            return PdfTable(widths, rows, padding, border, count, physicalIndent,
                when {
                    naturalWidths -> PdfTableWidthPolicy.FIT_CONTENTS
                    automaticLayout && !singleBandRule -> PdfTableWidthPolicy.AUTO_FIT
                    else -> PdfTableWidthPolicy.FIXED
                },
                preferredWidth = finalTableWidth).withRightOverflow(maxOf(legacyOverflow, fixedOverflow)).withAlignment(alignment)
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
            val paragraphs = content.elements().map { readParagraph(it, partRelations, fields, allowPageBreak = false) }
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

        fun readDrawingBox(node: XmlElement, partRelations: Map<String, String>, fields: Boolean = false,
            firstBodyParagraph: Boolean = false, isolatedFooterFrames: Boolean = false): PdfTextBox {
            source.current = node
            val wps = "http://schemas.microsoft.com/office/word/2010/wordprocessingShape"
            val anchor = node.elements().singleOrNull() ?: reject("Invalid floating drawing")
            check(anchor.name.namespaceURI == wp && anchor.name.localPart == "anchor", "Floating anchor required")
            val footer = fields && provenance[node]?.matches(Regex("word/footer[0-9]+[.]xml")) == true
            val leavesOtherObjects = anchor.attribute("", "allowOverlap") in setOf("0", "false")
            val order = WordDrawingOrder.read(anchor, separatedFooter = footer) { origin, message ->
                source.current = origin
                reject(message)
            }

            fun one(parent: XmlElement, ns: String, name: String) = parent.elements().singleOrNull { it.name.namespaceURI == ns && it.name.localPart == name } ?: reject("Missing or duplicate drawing property")

            fun numeric(value: String?) = value?.toFloatOrNull()?.takeIf { it.isFinite() } ?: reject("Invalid drawing coordinate")

            fun offset(position: XmlElement): Float {
                val coordinate = one(position, wp, "posOffset")
                check(coordinate.attributes.isEmpty() && coordinate.elements().isEmpty(), "Invalid frame offset structure")
                val raw = coordinate.children.filterIsInstance<XmlText>().joinToString("") { it.text }
                return numeric(raw) / 12700
            }

            fun horizontal(width: Float): Pair<Float, PdfAlignment?> {
                val position = one(anchor, wp, "positionH")
                source.current = position
                val reference = position.attribute("", "relativeFrom")
                check(position.attributes.size == 1 && position.elements().size == 1 &&
                    (reference in setOf("page", "margin", "column") || footer && reference == "rightMargin"),
                    "Unsupported horizontal frame reference")
                val origin = when (reference) {
                    "page" -> 0f
                    "rightMargin" -> page.width - page.margins.right
                    else -> page.margins.left
                }
                val coordinate = position.elements().single()
                if (coordinate.name.namespaceURI == wp && coordinate.name.localPart == "posOffset")
                    return (origin + offset(position)) to null
                check(reference != "rightMargin", "Right margin frame placement requires an explicit offset")
                check(coordinate.name.namespaceURI == wp && coordinate.name.localPart == "align" &&
                    coordinate.attributes.isEmpty() && coordinate.elements().isEmpty(), "Unsupported horizontal frame alignment")
                val alignment = when (coordinate.children.filterIsInstance<XmlText>().joinToString("") { it.text }) {
                    "left" -> PdfAlignment.LEFT
                    "center" -> PdfAlignment.CENTER
                    "right" -> PdfAlignment.RIGHT
                    else -> reject("Unsupported horizontal frame alignment")
                }
                if (reference == "page") return 0f to alignment
                val remaining = page.width - page.margins.left - page.margins.right - width
                val shift = when (alignment) {
                    PdfAlignment.CENTER -> remaining / 2f
                    PdfAlignment.RIGHT -> remaining
                    else -> 0f
                }
                return (origin + shift) to null
            }

            val extent = one(anchor, wp, "extent")
            check(anchor.elements().all { it.name.namespaceURI == wp && it.name.localPart in setOf("simplePos", "positionH", "positionV", "extent", "effectExtent", "wrapNone", "docPr", "cNvGraphicFramePr") ||
                it.name.namespaceURI == drawing && it.name.localPart == "graphic" ||
                it.name.namespaceURI == wp14 && it.name.localPart in setOf("sizeRelH", "sizeRelV") }, "Unsupported floating shape")
            one(anchor, wp, "wrapNone")
            WordInlineImagePadding.read(one(anchor, wp, "effectExtent")) { origin, message -> source.current = origin; reject(message) }
            val graphic = one(one(anchor, drawing, "graphic"), drawing, "graphicData")
            check(graphic.attribute("", "uri") == wps && graphic.elements().size == 1, "Unsupported floating graphic")
            val shape = one(graphic, wps, "wsp")
            check(shape.elements().size == 4 && shape.elements().all { it.name.namespaceURI == wps && it.name.localPart in setOf("cNvSpPr", "spPr", "txbx", "bodyPr") }, "Unsupported shape content")
            check(one(shape, wps, "cNvSpPr").attribute("", "txBox") in setOf("1", "true"), "Text frame required")
            val props = one(shape, wps, "spPr")
            val passivePaint = WordFramePaint.passivePaintMetadata(props) { origin, message -> source.current = origin; reject(message) }
            props.elements().firstOrNull { it !in passivePaint &&
                (it.name.namespaceURI != drawing || it.name.localPart !in setOf("xfrm", "prstGeom", "solidFill", "noFill", "ln")) }?.let { unsupported ->
                source.current = unsupported
                reject("Unsupported shape effect or geometry: ${unsupported.name.localPart.take(64)}")
            }
            val transform = one(props, drawing, "xfrm")
            check(transform.attributes.keys.all { it.namespaceURI.isEmpty() && it.localPart == "rot" }, "Flipped frames are unsupported")
            val origin = one(transform, drawing, "off")
            check(origin.attribute("", "x") == "0" && origin.attribute("", "y") == "0", "Unsupported local transform")
            val size = one(transform, drawing, "ext")
            check(size.attributes == extent.attributes && one(props, drawing, "prstGeom").attribute("", "prst") == "rect", "Inconsistent frame geometry")
            val rotation = (transform.attribute("", "rot")?.let(::numeric) ?: 0f) / 60000

            val bodyProps = one(shape, wps, "bodyPr")
            val autoFit = WordFrameTextMetadata.validate(bodyProps, rotation == 0f) { origin, message ->
                source.current = origin
                reject(message)
            }
            if (autoFit == null) {
                check(bodyProps.attributes.keys.all { it.namespaceURI.isEmpty() && it.localPart in setOf("rot", "vert", "wrap", "lIns", "tIns", "rIns", "bIns", "anchor", "anchorCtr", "upright", "vertOverflow") }, "Unsupported text frame policy")
                check(bodyProps.attribute("", "rot") == "0" && bodyProps.attribute("", "vert") == "horz" &&
                    bodyProps.attribute("", "wrap") == "square", "Unsupported text direction")
                check(bodyProps.attribute("", "vertOverflow") in setOf(null, "overflow"), "Unsupported fixed frame overflow policy")
                check(bodyProps.elements().size == 1 && one(bodyProps, drawing, "noAutofit").attributes.isEmpty(), "Frame autofitting is unsupported")
            }
            val content = one(one(shape, wps, "txbx"), word, "txbxContent")
            children(content, setOf("p"))
            val strokes = props.elements().filter { it.name.namespaceURI == drawing && it.name.localPart == "ln" }
            check(strokes.size <= 1, "Duplicate shape border")
            val border = strokes.singleOrNull()?.let { line ->
                WordFramePaint.stroke(line, themeColors) { origin, message -> source.current = origin; reject(message) }
            }
            val paragraphs = content.elements().map { readParagraph(it, partRelations, fields, allowPageBreak = false) }
            val width = numeric(extent.attribute("", "cx")) / 12700
            val (left, horizontalPageAlignment) = horizontal(width)
            if (autoFit == PdfTextBoxAutoFit.WIDTH_AND_HEIGHT && horizontalPageAlignment == null) {
                val coordinate = one(anchor, wp, "positionH").elements().single()
                source.current = coordinate
                check(coordinate.name.localPart == "posOffset" ||
                    coordinate.children.filterIsInstance<XmlText>().joinToString("") { it.text } == "left",
                    "Unwrapped autofit requires a fixed horizontal position")
            }
            val vertical = one(anchor, wp, "positionV")
            source.current = vertical
            val reference = vertical.attribute("", "relativeFrom")
            check(vertical.attributes.size == 1 && vertical.elements().size == 1 &&
                (reference == "page" || reference == "paragraph" && firstBodyParagraph),
                "Paragraph-relative frames require the first body paragraph of a section")
            val verticalCoordinate = vertical.elements().single()
            val verticalPageAlignment = if (reference == "page" && verticalCoordinate.name.namespaceURI == wp &&
                verticalCoordinate.name.localPart == "align" && verticalCoordinate.attributes.isEmpty() && verticalCoordinate.elements().isEmpty()) {
                when (verticalCoordinate.children.filterIsInstance<XmlText>().joinToString("") { it.text }) {
                    "top" -> PdfVerticalAlignment.TOP
                    "center" -> PdfVerticalAlignment.CENTER
                    "bottom" -> PdfVerticalAlignment.BOTTOM
                    else -> reject("Unsupported vertical frame alignment")
                }
            } else null
            if (leavesOtherObjects) {
                // The page-band layouter rejects overlap after resolving all of the footer objects.
                check(footer && autoFit == null && rotation == 0f && reference == "page" &&
                    verticalCoordinate.name.localPart == "posOffset" &&
                    one(anchor, wp, "positionH").elements().single().name.localPart == "posOffset",
                    "A no-overlap footer frame requires fixed physical coordinates")
            }
            val top = if (verticalPageAlignment == null) offset(vertical) else 0f
            val placement = if (reference == "paragraph") PdfBoxAnchor.PARAGRAPH else PdfBoxAnchor.PAGE
            val pageAlignment = if (horizontalPageAlignment != null || verticalPageAlignment != null) {
                check(reference == "page" && rotation == 0f, "Physical page alignment requires an unrotated page frame")
                PdfTextBoxPageAlignment(horizontalPageAlignment, verticalPageAlignment)
            } else null
            source.current = node
            return PdfTextBox(left, top, width,
                numeric(extent.attribute("", "cy")) / 12700, paragraphs, placement,
                rotation,
                PdfMargins(numeric(bodyProps.attribute("", "tIns")) / 12700, numeric(bodyProps.attribute("", "rIns")) / 12700,
                    numeric(bodyProps.attribute("", "bIns")) / 12700, numeric(bodyProps.attribute("", "lIns")) / 12700),
                WordFramePaint.fill(props, themeColors) { origin, message -> source.current = origin; reject(message) }, border,
                when (bodyProps.attribute("", "anchor")) { "t" -> PdfVerticalAlignment.TOP; "ctr" -> PdfVerticalAlignment.CENTER; "b" -> PdfVerticalAlignment.BOTTOM; else -> reject("Unsupported vertical alignment") }).withDrawingOrder(order).withAutoFit(autoFit).withPageAlignment(pageAlignment)
                .withVisibleTextOverflow((!fields || isolatedFooterFrames && placement == PdfBoxAnchor.PAGE) &&
                    autoFit == null && rotation == 0f && bodyProps.attribute("", "anchorCtr") in setOf(null, "0", "false"))
        }

        fun omitEmptyLegacyBandFrame(paragraph: XmlElement, partRelations: Map<String, String>): Boolean {
            val properties = paragraph.one("pPr") ?: return false
            val frame = properties.one("framePr") ?: return false
            val geometry = mapOf("wrap" to "around", "vAnchor" to "text", "hAnchor" to "margin", "xAlign" to "center", "y" to "1")
            if (paragraph.elements() != listOf(properties) || frame.attributes.size != geometry.size ||
                geometry.any { (name, value) -> frame.value(name) != value } ||
                frame.elements().isNotEmpty() || frame.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() } ||
                paragraph.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() }) return false

            val projected = XmlElement(paragraph.name, paragraph.attributes, paragraph.namespaces)
            val style = XmlElement(properties.name, properties.attributes, properties.namespaces)
            style.children += properties.children.filterNot { it === frame }
            projected.children += style
            val marker = readParagraph(projected, partRelations, fields = true)
            source.current = frame
            check(unprintedBand(listOf(marker)) && !marker.format.pageBreakBefore && marker.background == null &&
                marker.runs.all { (it as? PdfTextRun)?.style?.background == null },
                "Visible or paginating legacy frames are not qualified in page bands")
            return true
        }

        fun verifyEmptyTableFollower(paragraph: XmlElement, spacing: XmlElement, partRelations: Map<String, String>) {
            val properties = requireNotNull(paragraph.one("pPr"))
            val variant = XmlElement(paragraph.name, paragraph.attributes, paragraph.namespaces)
            val copiedProperties = XmlElement(properties.name, properties.attributes, properties.namespaces)
            val copiedSpacing = XmlElement(spacing.name, LinkedHashMap(spacing.attributes), spacing.namespaces)
            copiedSpacing.attributes.keys.removeAll { it.namespaceURI == word && it.localPart in setOf("line", "lineRule") }
            copiedSpacing.children += spacing.children
            copiedProperties.children += properties.children.map { if (it === spacing) copiedSpacing else it }
            variant.children += copiedProperties
            neighbors[variant] = neighbors[paragraph] ?: (null to null)
            val marker = readParagraph(variant, partRelations, measureMark = false)
            source.current = spacing
            check(unprintedBand(listOf(marker)) && marker.background == null && !marker.format.pageBreakBefore &&
                !marker.format.keepWithNext && marker.spaceBefore == 0f && marker.spaceAfter == 0f,
                "An empty zero-height paragraph must have no paint, leading space or pagination effect")
        }

        fun readBlocks(root: XmlElement, partRelations: Map<String, String>, fields: Boolean): List<PdfBlock> {
            val emptyFollowers = if (fields) emptyMap() else WordZeroHeightParagraphs.tableFollowers(root)
            return root.elements().filter { it.name.localPart != "sectPr" }.flatMapIndexed { index, block ->
                when (block.name.localPart) {
                    "p" -> {
                        if (fields && omitEmptyLegacyBandFrame(block, partRelations)) return@flatMapIndexed emptyList()
                        emptyFollowers[block]?.let { spacing ->
                            verifyEmptyTableFollower(block, spacing, partRelations)
                            return@flatMapIndexed emptyList()
                        }
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
                        val firstBodyParagraph = index == 0 && !fields && images.isEmpty() && paragraph.runs.none { it is PdfPageBreak }
                        val isolatedHeaderPicture = fields && root.name.localPart == "hdr" && root.elements().size == 1 &&
                            frames.size == 1 && images.size == 1 && unprintedBand(listOf(paragraph)) &&
                            paragraph.background == null && paragraph.lines.isEmpty() && paragraph.borders == null
                        val isolatedFooterFrames = fields && root.name.localPart == "ftr" && root.elements().size == 1 &&
                            boxes.isNotEmpty() && images.isEmpty() && boxes.all { it.name.localPart == "drawing" } &&
                            unprintedBand(listOf(paragraph)) && paragraph.background == null &&
                            paragraph.lines.isEmpty() && paragraph.borders == null
                        boxes.map { if (it.name.localPart == "pict") readBox(it, partRelations, fields)
                            else readDrawingBox(it, partRelations, fields, firstBodyParagraph, isolatedFooterFrames) } +
                            paragraph.copy(anchoredImages = images.map {
                                readAnchoredImage(it, partRelations, fields, isolatedHeaderPicture)
                            })
                    }
                    "tbl" -> listOf(readTable(block, partRelations, fields))
                    else -> reject("Unsupported block")
                }
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
            val hasEmptySectionMarkAfterTable = index < ranges.lastIndex && content.last() in sectionEnds &&
                headerReferences.isEmpty() && footerReferences.isEmpty() &&
                section.one("pgNumType") == null && content.getOrNull(content.lastIndex - 1)?.name?.localPart == "tbl" &&
                content.last().elements().singleOrNull()?.name?.localPart == "pPr"
            // Word does not create a page just for this unpainted mark at a next-page section break.
            val blocks = readBlocks(range, relations, false).let { blocks ->
                val terminal = blocks.lastOrNull() as? PdfParagraph
                if (hasEmptySectionMarkAfterTable && terminal != null && isUnpaintedSectionTerminator(terminal)) blocks.dropLast(1) else blocks
            }
            val header = band("header", headerReferences["default"])
            val footer = band("footer", footerReferences["default"])
            val first = if (section.one("titlePg")?.let(::pageToggle) == true)
                PdfPageBands(band("header", headerReferences["first"]), band("footer", footerReferences["first"])) else null
            val even = if (evenAndOddHeaders)
                PdfPageBands(band("header", headerReferences["even"]), band("footer", footerReferences["even"])) else null
            val flow = PdfFlow(header, footer, firstPageBands = first, evenPageBands = even)
            val numbering = pageNumbering(section)
            val frame = section.one("pgBorders")?.let {
                WordPageBorders.read(it, page) { origin, message -> source.current = origin; reject(message) }
            }
            PdfPageSequence(page, blocks, flow, numbering.copy(alignParity = evenAndOddHeaders && numbering.start != null))
                .withPageFrame(frame)
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
        if (notes.isNotEmpty() && sequences.size != 1) {
            source.current = body
            activePart = "word/document.xml"
            reject("Notes require one paginated section; section-boundary note layout is not yet qualified")
        }
        if (missingContentsStyles.isNotEmpty()) {
            val used = mutableSetOf<Int>()
            fun collect(block: PdfBlock) {
                context.checkActive()
                when (block) {
                    is PdfParagraph -> block.semantics?.level?.let { used += it }
                    is PdfTextBox -> block.paragraphs.forEach(::collect)
                    is PdfTable -> block.rows.forEach { row -> row.cells.forEach { cell -> cell.blocks.forEach(::collect) } }
                    is PdfImage -> Unit
                }
            }
            for (sequence in sequences) {
                sequence.blocks.forEach(::collect)
                val flowed = sequence.flow
                val pageBands = listOfNotNull(flowed?.header, flowed?.footer, flowed?.firstPageBands?.header,
                    flowed?.firstPageBands?.footer, flowed?.evenPageBands?.header, flowed?.evenPageBands?.footer)
                pageBands.forEach { it.blocks.forEach(::collect) }
            }
            notes.forEach { it.paragraphs.forEach(::collect) }
            for ((node, missing) in missingContentsStyles) {
                source.current = node
                activePart = provenance[node] ?: "word/document.xml"
                check(missing.none { it in used }, "Explicit contents paragraph styles are required for used outline levels")
            }
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
        val kerningThreshold: Float = 0f, val standardLigatures: Boolean = false, val openType: Boolean = false, val highAnsiFamily: String? = null, val characterSpacing: Float = 0f,
        val highlight: PdfColor? = null, val shading: PdfColor? = null, val automaticColor: Boolean = true,
        val paragraphBackground: PdfColor? = null, val horizontalScale: Float = 1f, val capitals: Boolean = false, val smallCaps: Boolean = false) {

        fun id(resolve: (String, Boolean, Boolean) -> String): String = resolve(
            requireNotNull(family) { "An explicit font family is required" }, bold, italic)

    }

    private fun fontRunsBound(condition: Boolean) {
        check(condition, "Mixed font selection exceeds the paragraph run limit")
    }

    private fun mixedFonts(face: Face) = face.highAnsiFamily != null && !face.family.equals(face.highAnsiFamily, true)

    private fun textStyle(face: Face): PdfTextStyle {
        val nominal = face.size ?: reject("Missing font size")
        val size = if (face.superscript) kotlin.math.round(nominal * 1.3f) / 2 else nominal
        val kern = if (face.kerningThreshold > 0 && size >= face.kerningThreshold)
            if (face.openType) PdfKerning.OPENTYPE else PdfKerning.PAIRS else PdfKerning.NONE
        val background = face.highlight ?: face.shading
        // Word's automatic ink follows shading, even when a highlight visually overrides that shading.
        val color = if (face.automaticColor) WordRunBackground.automaticForeground(face.shading ?: face.paragraphBackground) else face.color
        return PdfTextStyle(face.id(fontId), size, color, face.languageTag, face.underline, if (face.superscript) nominal - size else 0f, face.strikethrough)
            .withFontFeatures(PdfFontFeatures(kern, face.standardLigatures && face.openType))
            .withSyntheticBold(syntheticBold(requireNotNull(face.family), face.bold, face.italic))
            .withCharacterSpacing(face.characterSpacing)
            .withBackground(background)
            .withHorizontalScale(face.horizontalScale)
    }

    private val underscoredLocale = Regex("[A-Za-z]{2,3}_[A-Za-z]{2}")

    private fun runProperties(property: XmlElement?, initial: Face, themeFonts: Map<String, String>,
        themeColors: WordThemeColors, characterBase: Face?): Face {
        if (property == null) return initial
        val word14 = "http://schemas.microsoft.com/office/word/2010/wordml"
        children(property, setOf("rStyle", "rFonts", "sz", "b", "i", "color", "lang", "szCs", "bCs", "iCs", "rtl", "u", "vertAlign", "vanish", "webHidden", "strike", "kern", "spacing", "dstrike", "w", "highlight", "shd", "caps", "smallCaps", "bdr"), setOf(word14 to "ligatures", WordTextOutline.NS to "textOutline"))
        property.elements().singleOrNull { it.name.namespaceURI == WordTextOutline.NS && it.name.localPart == "textOutline" }?.let {
            WordTextOutline.requireInactive(it) { origin, message -> source.current = origin; reject(message) }
        }
        var result = initial
        property.one("bdr")?.let { node ->
            WordInactiveRunProperties.requireInvisibleBorder(node) { origin, message -> source.current = origin; reject(message) }
        }
        property.one("highlight")?.let { node ->
            result = result.copy(highlight = WordRunBackground.highlight(node) { origin, message -> source.current = origin; reject(message) })
        }
        property.one("shd")?.let { node ->
            val fill = themeColors.runFill(node)
            if (node.value("val") != "nil") result = result.copy(shading = fill)
        }
        for (node in listOf("dstrike").mapNotNull { property.one(it) }) {
            WordInactiveRunProperties.requireInactive(node) { origin, message -> source.current = origin; reject(message) }
        }
        property.one("w")?.let { node ->
            result = result.copy(horizontalScale = WordInactiveRunProperties.horizontalScale(node) { origin, message -> source.current = origin; reject(message) })
        }
        property.one("spacing")?.let { node ->
            result = result.copy(characterSpacing = WordRunSpacing.read(node) { origin, message -> source.current = origin; reject(message) })
        }
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
            check(value in setOf("none", "standard", "standardContextual"), "Only standard and standard-contextual OpenType ligatures are qualified")
            result = result.copy(standardLigatures = value != "none")
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
            attributes(it, setOf("ascii", "hAnsi", "asciiTheme", "hAnsiTheme", "cs", "eastAsia", "hint"))
            check(it.value("hint") in setOf(null, "default"), "Only default font hints are qualified")

            fun family(name: String): String? = it.value(name + "Theme")?.let { key ->
                val kind = when (key) { "majorAscii", "majorHAnsi" -> "major"; "minorAscii", "minorHAnsi" -> "minor"; else -> reject("Unsupported theme font role") }
                themeFonts[kind] ?: reject("Unresolved theme font")
            } ?: it.value(name) ?: if (name == "hAnsi") initial.highAnsiFamily ?: initial.family else initial.family

            val family = family("ascii") ?: reject("Explicit Latin font required")
            val highAnsi = family("hAnsi") ?: reject("Explicit high-ANSI font required")
            check(family.isNotBlank() && highAnsi.isNotBlank(), "Empty Latin font family")
            check(it.value("cs") == null || it.value("cs") == family, "Separate complex-script font faces are outside this proof")
            check(it.value("eastAsia") == null || it.value("eastAsia") == family, "Separate East Asian font faces are outside this profile")
            result = result.copy(family = family, highAnsiFamily = highAnsi)
        }
        property.one("lang")?.let {
            attributes(it, setOf("val"))
            check(it.elements().isEmpty() && it.children.filterIsInstance<XmlText>().all { text -> text.text.isBlank() }, "Invalid run language")
            it.value("val")?.let { language ->
                // Word's no-proofing marker clears the inherited language and its casing rules.
                val resolved = when {
                    language.equals("x-none", ignoreCase = true) -> null
                    language.equals("es-ES_tradnl", ignoreCase = true) -> "es-ES-u-co-trad"
                    underscoredLocale.matches(language) -> language.replace('_', '-')
                    else -> language
                }
                PdfTextStyle("regular", 11f, languageTag = resolved)
                result = result.copy(languageTag = resolved)
            }
        }
        property.one("sz")?.let { result = result.copy(size = number(it.value("val")) / 2) }

        fun enabled(node: XmlElement): Boolean = when (node.value("val")) { null, "true", "1", "on" -> true; "false", "0", "off" -> false; else -> reject("Invalid toggle") }

        // Hidden runs were removed from the private DOCX intermediary using resolved Word hidden-text formatting.
        for (name in listOf("vanish", "webHidden")) property.one(name)?.let {
            attributes(it, setOf("val")); children(it, emptySet()); enabled(it)
        }
        property.one("rtl")?.let { attributes(it, setOf("val")); check(it.elements().isEmpty(), "Invalid run direction"); result = result.copy(rtl = enabled(it)) }
        property.one("smallCaps")?.let { node ->
            val small = WordCapitalization.enabled(node) { origin, message -> source.current = origin; reject(message) }
            result = result.copy(smallCaps = characterBase?.smallCaps?.xor(small) ?: small)
        }
        property.one("caps")?.let {
            val caps = WordCapitalization.enabled(it) { origin, message -> source.current = origin; reject(message) }
            result = result.copy(capitals = characterBase?.capitals?.xor(caps) ?: caps)
        }
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
            result = result.copy(color = themeColors.resolve(it), automaticColor = it.value("val") == "auto" && it.value("themeColor") in setOf(null, "none"))
        }
        return result
    }

    private data class Geometry(val before: Float = 0f, val after: Float = 0f, val line: Float? = null,
        val minimum: Float? = null,
        val overflowPunctuation: Boolean? = null, val suppressAutoHyphens: Boolean = false,
        val autoBefore: Boolean = false, val autoAfter: Boolean = false,
        val mirror: Boolean = false,
        val background: PdfColor? = null,
        val alignment: PdfAlignment = PdfAlignment.LEFT, val left: Float = 0f, val right: Float = 0f, val first: Float = 0f,
        val keepWithNext: Boolean = false, val keepLines: Boolean = false, val widowControl: Boolean = true, val pageBreakBefore: Boolean = false, val outline: Int? = null, val listTab: Float? = null, val tabStops: List<PdfTabStop> = emptyList(), val automatic: PdfAutomaticLineSpacing? = PdfAutomaticLineSpacing(), val contextual: Boolean = false, val direction: PdfTextDirection? = null, val borders: PdfParagraphBorders? = null,
        val beforeLines: Float? = null, val afterLines: Float? = null, val textAlignment: XmlElement? = null)

    private fun paragraphProperties(property: XmlElement, initial: Geometry, themeColors: WordThemeColors, styleMetadata: Boolean): Geometry {
        NativeCellPagination.validateProperties(property) { node, message ->
            source.current = node
            activePart = provenance[node] ?: activePart
            reject(message)
        }
        children(property, setOf("pStyle", "bidi", "contextualSpacing", "spacing", "jc", "ind", "rPr", "mirrorIndents", "widowControl", "keepNext", "keepLines", "pageBreakBefore", "numPr", "outlineLvl", "tabs", "pBdr", "overflowPunct", "suppressAutoHyphens", "shd", "textAlignment"))
        var result = initial
        property.one("shd")?.let { node ->
            val fill = themeColors.runFill(node)
            if (node.value("val") != "nil") result = result.copy(background = fill)
        }
        property.one("pBdr")?.let { result = result.copy(borders = lineReader.borders(it, result.borders)) }

        fun flag(node: XmlElement): Boolean {
            attributes(node, setOf("val"))
            return when (node.value("val")) { null, "true", "1", "on" -> true; "false", "0", "off" -> false; else -> reject("Invalid paragraph toggle") }
        }

        property.one("bidi")?.let { check(it.elements().isEmpty(), "Invalid paragraph direction"); result = result.copy(direction = if (flag(it)) PdfTextDirection.RIGHT_TO_LEFT else PdfTextDirection.LEFT_TO_RIGHT) }
        for (name in listOf("overflowPunct", "suppressAutoHyphens")) property.one(name)?.let {
            children(it, emptySet())
            check(it.children.filterIsInstance<XmlText>().all { text -> text.text.isBlank() }, "Invalid paragraph line-breaking preference")
            val enabled = flag(it)
            result = if (name == "overflowPunct") result.copy(overflowPunctuation = enabled) else result.copy(suppressAutoHyphens = enabled)
        }
        property.one("outlineLvl")?.let {
            attributes(it, setOf("val"))
            children(it, emptySet())
            val level = it.value("val")?.toIntOrNull()?.takeIf { raw -> raw in 0..9 } ?: reject("Unsupported outline level")
            if (level == 9 && !styleMetadata) reject("Direct body outline overrides are not qualified")
            result = result.copy(outline = level.takeUnless { raw -> raw == 9 })
        }
        property.one("tabs")?.let { tabs ->
            children(tabs, setOf("tab"))
            val merged = result.tabStops.associateBy { it.position }.toMutableMap()
            val positions = mutableSetOf<Float>()
            var numTab = result.listTab
            for (tab in tabs.elements()) {
                attributes(tab, setOf("val", "pos", "leader"))
                check(tab.elements().isEmpty() && tab.children.filterIsInstance<XmlText>().all { it.text.isBlank() }, "Invalid tab stop")
                val position = tab.value("pos")?.toIntOrNull()?.takeIf { it in -288_000..288_000 } ?: reject("Invalid tab position")
                val pos = position / 20f
                check(positions.add(pos), "Duplicate source tab position")
                if (pos < 0f) {
                    check(tab.value("val") in setOf("left", "clear") && tab.value("leader") in setOf(null, "none"),
                        "Only negative left tab stops without leaders and negative clear stops are qualified")
                    continue
                }
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
        property.one("textAlignment")?.let { node ->
            attributes(node, setOf("val"))
            children(node, emptySet())
            val value = node.value("val")
            check(value in setOf("auto", "baseline", "top", "center", "bottom"), "Unsupported effective paragraph text alignment")
            result = result.copy(textAlignment = node.takeUnless { value == "auto" || value == "baseline" })
        }
        property.one("mirrorIndents")?.let { check(it.elements().isEmpty(), "Invalid mirrored indentation"); result = result.copy(mirror = flag(it)) }
        property.one("spacing")?.let {
            attributes(it, setOf("before", "after", "line", "lineRule", "beforeAutospacing", "afterAutospacing", "beforeLines", "afterLines"))
            check(it.elements().isEmpty() && it.children.filterIsInstance<XmlText>().all { text -> text.text.isBlank() }, "Invalid paragraph spacing")

            fun auto(name: String, inherited: Boolean): Boolean = when (it.value(name)) {
                null -> inherited; "true", "1", "on" -> true; "false", "0", "off" -> false
                else -> reject("Invalid automatic paragraph spacing")
            }

            result = result.copy(autoBefore = auto("beforeAutospacing", result.autoBefore), autoAfter = auto("afterAutospacing", result.autoAfter))
            val line = it.value("line")
            // A rule without a measurement does not replace the inherited physical line box.
            when (it.value("lineRule") ?: "auto") {
                "exact" -> if (line != null) result = result.copy(line = twips(line), automatic = null, minimum = null)
                "auto" -> if (line != null) result = result.copy(line = null, automatic = PdfAutomaticLineSpacing(number(line) / 240f), minimum = null)
                "atLeast" -> if (line != null) result = result.copy(line = null, automatic = null, minimum = twips(line))
                else -> reject("Unsupported line spacing policy")
            }
            fun inLines(name: String, inherited: Float?): Float? {
                val value = it.value(name) ?: return inherited
                return WordNumericSpellings.spacingInLineUnits(it, value) { node, message ->
                    source.current = node
                    reject(message)
                }.takeIf { points -> points > 0f }
            }

            result = result.copy(before = it.value("before")?.let(::twips) ?: result.before,
                after = it.value("after")?.let(::twips) ?: result.after,
                beforeLines = inLines("beforeLines", result.beforeLines), afterLines = inLines("afterLines", result.afterLines))
        }
        property.one("jc")?.let { result = result.copy(alignment = when (it.value("val")) {
            "left" -> PdfAlignment.LEFT; "center" -> PdfAlignment.CENTER; "right" -> PdfAlignment.RIGHT; "both" -> PdfAlignment.JUSTIFY; else -> reject("Unsupported alignment")
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

    private fun image(node: XmlElement, relations: Map<String, String>, parts: Map<String, ByteArray>,
                      uniformCellPictureExtent: Boolean = false): PdfImage {
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
        val preferredSvg = inline.descendants().singleOrNull { it.name.namespaceURI == drawing && it.name.localPart == "blip" }?.let { element ->
            val reference = WordPictureMetadata.svgReference(element) { invalid, message -> source.current = invalid; reject(message) }
                ?: return@let null
            source.current = element
            val fallback = relations[element.attribute(rel, "embed")]
            check(fallback != null && fallback.startsWith("media/") && !fallback.endsWith(".svg", true) &&
                "word/$fallback" in parts, "Missing raster fallback for embedded SVG")
            val target = relations[reference.relationship]
            check(target != null && target.startsWith("media/") && target.endsWith(".svg", true) &&
                "word/$target" in parts, "Missing embedded SVG picture")
            reference.consume(element)
            requireNotNull(target)
        }
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
            WordInlineImagePadding.read(effect) { origin, message -> source.current = origin; reject(message) }
        }
        val extent = inline.elements().singleOrNull { it.name.namespaceURI == wp && it.name.localPart == "extent" } ?: reject("Missing image extent")
        val blip = inline.descendants().singleOrNull { it.name.namespaceURI == drawing && it.name.localPart == "blip" } ?: reject("Missing image relationship")
        imageAttributes(blip) { name, value ->
            name.namespaceURI == rel && name.localPart == "embed" || WordPictureMetadata.compressionState(name, value)
        }
        source.current = blip
        val target = preferredSvg ?: relations[blip.attribute(rel, "embed")] ?: reject("Missing embedded image")
        at(blip, target.startsWith("media/"), "Invalid image relationship")
        val transform = inline.descendants().singleOrNull { it.name.namespaceURI == drawing && it.name.localPart == "xfrm" } ?: reject("Missing image transform")
        imageAttributes(transform) { _, _ -> false }
        val offset = transform.elements().singleOrNull { it.name.localPart == "off" } ?: reject("Missing image offset")
        at(offset, offset.attribute("", "x") == "0" && offset.attribute("", "y") == "0", "Unsupported image offset")
        val transformedExtent = transform.elements().singleOrNull { it.name.localPart == "ext" } ?: reject("Missing picture extent")
        val expectedWidth = extent.attribute("", "cx")
        val expectedHeight = extent.attribute("", "cy")
        val innerWidth = transformedExtent.attribute("", "cx")
        val innerHeight = transformedExtent.attribute("", "cy")
        at(transformedExtent, innerWidth == expectedWidth && innerHeight == expectedHeight ||
            uniformCellPictureExtent && WordCellPictureExtent.isUniform(expectedWidth, expectedHeight, innerWidth, innerHeight),
            "Nonmatching picture transforms are unsupported")
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
        } else if (target.endsWith(".svg", true)) svgPictures.getOrPut(target) {
            val image = WordSvgPicture.rasterize(original, context) { message ->
                throw NativeDocxReadException(message, "word/$target",
                    dev.nuaforma.contracts.DocumentSourceContext(message.take(256), null, "svg", ""))
            }
            svgPictureBytes += image.size
            budget(svgPictureBytes <= minOf(8_388_608L, context.limits.maxExpandedBytes))
            image
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

    private fun isUnpaintedSectionTerminator(paragraph: PdfParagraph): Boolean =
        paragraph.format.exactLineHeight != null && !paragraph.format.pageBreakBefore && !paragraph.format.keepWithNext &&
            !paragraph.format.keepLines && paragraph.spaceBefore == 0f && paragraph.spaceAfter == 0f &&
            paragraph.format.leftIndent == 0f && paragraph.format.rightIndent == 0f && paragraph.format.firstLineIndent == 0f &&
            paragraph.pageOffset == null && paragraph.background == null && unprintedBand(listOf(paragraph)) &&
            paragraph.runs.none { (it as? PdfTextRun)?.style?.background != null }

    private val trashItem = Regex("\\[trash]/[0-9A-Fa-f]{4}\\.dat")

    private fun unpack(bytes: ByteArray): Map<String, ByteArray> {
        budget(bytes.size <= minOf(8_388_608L, context.limits.maxTemplateBytes))
        val parts = linkedMapOf<String, ByteArray>()
        var total = 0
        try {
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    context.checkActive()
                    val entry = zip.nextEntry ?: break
                    budget(parts.size < minOf(256, context.limits.maxArchiveEntries))
                    check(entry.name !in parts && !entry.isDirectory, "Invalid or duplicate ZIP entries")
                    activePart = entry.name.take(256)
                    check(trashItem.matches(entry.name) || entry.name.matches(Regex("(?:\\[Content_Types]\\.xml|_rels/\\.rels|docMetadata/LabelInfo\\.xml|docProps/thumbnail\\.(?:jpeg|jpg|png|emf)|docProps/(?:app|core|custom)\\.xml|word/(?:(?:document|styles|stylesWithEffects|intelligence2|numbering|settings|footnotes|endnotes|fontTable|webSettings|comments(?:Extended|Ids|Extensible)?|people|(?:header|footer)[1-9][0-9]?)\\.xml|theme/theme1\\.xml|theme/_rels/theme1\\.xml\\.rels|_rels/(?:document|settings|footnotes|endnotes|fontTable|comments(?:Extended|Ids|Extensible)?|people|(?:header|footer)[1-9][0-9]?)\\.xml\\.rels|fonts/[A-Za-z0-9_.-]+\\.(?i:odttf|ttf)|media/[A-Za-z0-9_.-]+\\.(?i:png|emf|jpe?g|svg))|(?i:customXml)/(?:item(?:Props)?[1-9][0-9]?\\.xml|_rels/item[1-9][0-9]?\\.xml\\.rels))")), "Unsupported package part")
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
        val trashNames = parts.keys.filter { trashItem.matches(it) }
        if (trashNames.isNotEmpty()) {
            val types = parse(parts["[Content_Types].xml"] ?: reject("Missing content types"), "[Content_Types].xml")
            for (node in types.elements()) {
                source.current = node
                val associated = node.name.namespaceURI == "http://schemas.openxmlformats.org/package/2006/content-types" &&
                    (node.name.localPart == "Default" && node.attribute("", "Extension")?.equals("dat", true) == true ||
                        node.name.localPart == "Override" && node.attribute("", "PartName")?.removePrefix("/") in trashNames)
                check(!associated, "Physical OOXML trash items must not have a content type")
            }
            trashNames.forEach(parts::remove)
        }
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
        check(node.elements().groupBy { it.name.localPart }.all { (name, values) -> name in setOf("r", "p", "style", "tbl", "tr", "tc", "tblStylePr", "fldSimple", "t", "tab", "br", "cr", "noBreakHyphen", "softHyphen", "headerReference", "footerReference", "hyperlink", "bookmarkStart", "bookmarkEnd") || values.size == 1 }, "Duplicate property")
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
