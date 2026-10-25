package dev.nuaforma.docx.pdf.adapters.ooxml

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.internal.*
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Locale
import java.util.zip.ZipFile

/** Stage caller fonts with generated names and inspect the generated document's actual fonts. */
internal object PdfFonts {
    private const val WORD = NativeTextBoxes.WORD
    private const val DRAWING = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val OFFICE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    fun supplied(request: GenerationRequest, files: ConversionFiles, context: GenerationContext, prefix: String = ""): Set<String> {
        val raw = request.requirements.output.options["fonts"] as? List<*> ?: emptyList<Any?>()
        val declarations = raw.map { it as Map<*, *> }
        if (request.resources.keys != declarations.map { it["resource"] as String }.toSet()) invalid(RejectionReason.SUPPLY_EXACTLY_THE_DECLARED_PDF_FONT_RESOURCES)
        val paths = mutableMapOf<String, java.nio.file.Path>()
        val families = mutableSetOf<String>()
        var total = Files.list(files.directory.resolve("fonts")).use { paths -> paths.mapToLong { Files.size(it) }.sum() }
        for ((index, declaration) in declarations.withIndex()) {
            context.checkActive()
            val id = declaration["resource"] as String
            val path = paths.getOrPut(id) {
                val target = files.directory.resolve("fonts/${prefix}font-$index.otf")
                Files.newOutputStream(target).use { output -> request.resources.getValue(id).openStream().use {
                    PdfStreams.copy(it, output, minOf(16L * 1024 * 1024, minOf(64L * 1024 * 1024, context.limits.maxExpandedBytes) - total), context)
                } }
                total += Files.size(target)
                target
            }
            val metadata = PdfFontMetadata.read(Files.readAllBytes(path))
            if (declaration["family"] !in metadata.families || (declaration["face"] ?: "regular") != metadata.face) invalid(RejectionReason.PDF_FONT_FAMILY_OR_FACE_DOES_NOT_MATCH_ITS)
            metadata.requirePrintEmbedding()
            if (metadata.face == "regular") families.addAll(metadata.families)
        }
        return families
    }

    fun required(files: ConversionFiles, context: GenerationContext): Pair<Set<String>, Set<String>> {
        val required = mutableSetOf<String>()
        val embedded = mutableSetOf<String>()
        val xml = ConversionXml(context)
        var ascii = false
        var ansi = false
        fun read(zip: ZipFile, name: String): ByteArray {
            val entry = zip.getEntry(name) ?: invalid(RejectionReason.EMBEDDED_FONT_PART_IS_MISSING)
            val buffer = ByteArrayOutputStream()
            zip.getInputStream(entry).use { PdfStreams.copy(it, buffer, minOf(16L * 1024 * 1024, context.limits.maxExpandedBytes), context) }
            return buffer.toByteArray()
        }
        ZipFile(files.docx.toFile()).use { zip ->
            val relationships = zip.getEntry("word/_rels/fontTable.xml.rels")?.let { entry ->
                xml.read(read(zip, entry.name)).elements().associate { (it.attribute("", "Id") ?: invalid(RejectionReason.EMBEDDED_FONT_RELATIONSHIP_HAS_NO_ID)) to
                    (it.attribute("", "Target") ?: invalid(RejectionReason.EMBEDDED_FONT_RELATIONSHIP_HAS_NO_TARGET)) }
            } ?: emptyMap()
            var embeddedBytes = 0L
            for (entry in zip.entries().asSequence().filter { it.name.startsWith("word/") && it.name.endsWith(".xml") && !it.name.contains("/theme/") }) {
                val root = xml.read(read(zip, entry.name))
                val defaultNodes = root.descendants().filter { it.name.namespaceURI == WORD && it.name.localPart == "rPrDefault" }.flatMap { it.descendants() }.toSet()
                for (element in root.descendants()) {
                    context.checkActive()
                    if (element.name.namespaceURI == WORD && element.name.localPart == "rFonts") {
                        for ((name, value) in element.attributes) {
                            if (name.namespaceURI != WORD) continue
                            if (name.localPart.endsWith("theme", true)) unsupported(RejectionReason.PDF_FONT_RESOLUTION_CURRENTLY_REQUIRES_EXPLICIT_FONT_FAMILIES_INSTEAD)
                            if (name.localPart in setOf("ascii", "hAnsi", "eastAsia", "cs")) {
                                required.add(value)
                                if (entry.name == "word/styles.xml" && element in defaultNodes) {
                                    if (name.localPart == "ascii") ascii = true
                                    if (name.localPart == "hAnsi") ansi = true
                                }
                            }
                        }
                    }
                    if (element.name.namespaceURI == WORD && element.name.localPart == "sym") required.add(element.attribute(WORD, "font") ?: invalid(RejectionReason.SYMBOL_FONT_IS_MISSING))
                    if (element.name.namespaceURI == DRAWING && element.name.localPart in setOf("latin", "ea", "cs")) {
                        val family = element.attribute("", "typeface") ?: invalid(RejectionReason.DRAWING_FONT_IS_MISSING)
                        if (family.startsWith('+')) unsupported(RejectionReason.THEME_DRAWING_FONTS_ARE_NOT_SUPPORTED_BY_THIS_PDF)
                        if (family.isNotBlank()) required.add(family)
                    }
                    if (element.name.namespaceURI == NativeTextBoxes.VML && element.name.localPart == "textpath") {
                        // Shape-type definitions contain an unfilled text path, not rendered glyphs.
                        if (element.attribute("", "string").isNullOrEmpty()) continue
                        val style = element.attribute("", "style") ?: unsupported(RejectionReason.A_VML_TEXT_PATH_REQUIRES_AN_EXPLICIT_FONT_FAMILY)
                        val match = Regex("""(?:^|;)\s*font-family\s*:\s*(?:"((?:\\.|[^"\\])*)"|([^;]+))""").find(style)
                            ?: unsupported(RejectionReason.A_VML_TEXT_PATH_REQUIRES_AN_EXPLICIT_FONT_FAMILY)
                        val family = (match.groups[1]?.value ?: match.groups[2]!!.value.trim()).replace("\\\"", "\"").replace("\\\\", "\\")
                        required.add(family)
                    }
                    if (entry.name == "word/fontTable.xml" && element.name.namespaceURI == WORD && element.name.localPart == "font") {
                        val family = element.attribute(WORD, "name") ?: invalid(RejectionReason.EMBEDDED_FONT_FAMILY_IS_MISSING)
                        for (face in element.elements().filter { it.name.namespaceURI == WORD && it.name.localPart in setOf("embedRegular", "embedBold", "embedItalic", "embedBoldItalic") }) {
                            val target = relationships[face.attribute(OFFICE, "id")] ?: invalid(RejectionReason.EMBEDDED_FONT_RELATIONSHIP_IS_MISSING)
                            val name = java.nio.file.Path.of("word").resolve(target).normalize().toString().replace('\\', '/').removePrefix("/")
                            if (!name.startsWith("word/fonts/")) unsupported(RejectionReason.EMBEDDED_FONTS_MUST_REMAIN_INSIDE_THE_FONT_PACKAGE_DIRECTORY)
                            val bytes = read(zip, name)
                            embeddedBytes += bytes.size
                            if (embeddedBytes > minOf(64L * 1024 * 1024, context.limits.maxExpandedBytes)) PdfFailure.fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, RejectionReason.EMBEDDED_PDF_FONT_BUDGET_WAS_EXCEEDED)
                            val key = face.attribute(WORD, "fontKey") ?: invalid(RejectionReason.EMBEDDED_FONT_KEY_IS_MISSING)
                            val guid = "[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}"
                            if (!Regex("(?:$guid|\\{$guid\\})").matches(key) || bytes.size < 32) invalid(RejectionReason.EMBEDDED_FONT_KEY_IS_INVALID)
                            val hex = key.replace(Regex("[{}-]"), "")
                            val mask = (0..15).map { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }.reversed()
                            repeat(32) { bytes[it] = (bytes[it].toInt() xor mask[it % 16].toInt()).toByte() }
                            val metadata = PdfFontMetadata.read(bytes)
                            if (family !in metadata.families) invalid(RejectionReason.EMBEDDED_FONT_FAMILY_DOES_NOT_MATCH_ITS_METADATA)
                            val expected = when (face.name.localPart) { "embedBold" -> "bold"; "embedItalic" -> "italic"; "embedBoldItalic" -> "boldItalic"; else -> "regular" }
                            if (metadata.face != expected) invalid(RejectionReason.EMBEDDED_FONT_FACE_DOES_NOT_MATCH_ITS_METADATA)
                            metadata.requirePrintEmbedding()
                            if (metadata.face == "regular") embedded.addAll(metadata.families)
                        }
                    }
                    if (required.size > 128 || required.any { it.isBlank() || it.length > 128 || it.any(Char::isISOControl) }) invalid(RejectionReason.INVALID_OR_EXCESSIVE_PDF_FONT_DECLARATIONS)
                }
            }
        }
        if (!ascii || !ansi) unsupported(RejectionReason.PDF_FONT_PRESERVATION_REQUIRES_EXPLICIT_ASCII_AND_HANSI_DOCUMENT)
        return required to embedded
    }

    fun ensureAvailable(required: Set<String>, available: Set<String>) {
        if (!available.map { it.lowercase(Locale.ROOT) }.toSet().containsAll(required.map { it.lowercase(Locale.ROOT) })) {
            PdfFailure.fail(DiagnosticCodes.INVALID_REQUEST, RejectionReason.A_SELECTED_DOCUMENT_FONT_IS_UNAVAILABLE_SUPPLY_IT_AS)
        }
    }

    private fun invalid(reason: RejectionReason): Nothing = PdfFailure.fail(DiagnosticCodes.INVALID_REQUEST, reason)
    private fun unsupported(reason: RejectionReason): Nothing = PdfFailure.fail(DiagnosticCodes.UNSUPPORTED_INPUT, reason)
}
