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

    fun supplied(request: GenerationRequest, files: ConversionFiles, context: GenerationContext): Set<String> {
        val raw = request.requirements.output.options["fonts"] as? List<*> ?: emptyList<Any?>()
        val declarations = raw.map { it as Map<*, *> }
        if (request.resources.keys != declarations.map { it["resource"] as String }.toSet()) invalid("Supply exactly the declared PDF font resources")
        val paths = mutableMapOf<String, java.nio.file.Path>()
        val families = mutableSetOf<String>()
        var total = 0L
        for ((index, declaration) in declarations.withIndex()) {
            context.checkActive()
            val id = declaration["resource"] as String
            val path = paths.getOrPut(id) {
                val target = files.directory.resolve("fonts/font-$index.otf")
                Files.newOutputStream(target).use { output -> request.resources.getValue(id).openStream().use {
                    PdfStreams.copy(it, output, minOf(16L * 1024 * 1024, minOf(64L * 1024 * 1024, context.limits.maxExpandedBytes) - total), context)
                } }
                total += Files.size(target)
                target
            }
            val metadata = PdfFontMetadata.read(Files.readAllBytes(path))
            if (declaration["family"] !in metadata.families || (declaration["face"] ?: "regular") != metadata.face) invalid("PDF font family or face does not match its declaration")
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
            val entry = zip.getEntry(name) ?: invalid("Embedded font part is missing")
            val buffer = ByteArrayOutputStream()
            zip.getInputStream(entry).use { PdfStreams.copy(it, buffer, minOf(16L * 1024 * 1024, context.limits.maxExpandedBytes), context) }
            return buffer.toByteArray()
        }
        ZipFile(files.docx.toFile()).use { zip ->
            val relationships = zip.getEntry("word/_rels/fontTable.xml.rels")?.let { entry ->
                xml.read(read(zip, entry.name)).elements().associate { (it.attribute("", "Id") ?: invalid("Embedded font relationship has no ID")) to
                    (it.attribute("", "Target") ?: invalid("Embedded font relationship has no target")) }
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
                            if (name.localPart.endsWith("theme", true)) unsupported("PDF font resolution currently requires explicit font families instead of themes")
                            if (name.localPart in setOf("ascii", "hAnsi", "eastAsia", "cs")) {
                                required.add(value)
                                if (entry.name == "word/styles.xml" && element in defaultNodes) {
                                    if (name.localPart == "ascii") ascii = true
                                    if (name.localPart == "hAnsi") ansi = true
                                }
                            }
                        }
                    }
                    if (element.name.namespaceURI == WORD && element.name.localPart == "sym") required.add(element.attribute(WORD, "font") ?: invalid("Symbol font is missing"))
                    if (element.name.namespaceURI == DRAWING && element.name.localPart in setOf("latin", "ea", "cs")) {
                        val family = element.attribute("", "typeface") ?: invalid("Drawing font is missing")
                        if (family.startsWith('+')) unsupported("Theme drawing fonts are not supported by this PDF profile")
                        if (family.isNotBlank()) required.add(family)
                    }
                    if (element.name.namespaceURI == NativeTextBoxes.VML && element.name.localPart == "textpath") {
                        // Shape-type definitions contain an unfilled text path, not rendered glyphs.
                        if (element.attribute("", "string").isNullOrEmpty()) continue
                        val style = element.attribute("", "style") ?: unsupported("A VML text path requires an explicit font family")
                        val match = Regex("""(?:^|;)\s*font-family\s*:\s*(?:"((?:\\.|[^"\\])*)"|([^;]+))""").find(style)
                            ?: unsupported("A VML text path requires an explicit font family")
                        val family = (match.groups[1]?.value ?: match.groups[2]!!.value.trim()).replace("\\\"", "\"").replace("\\\\", "\\")
                        required.add(family)
                    }
                    if (entry.name == "word/fontTable.xml" && element.name.namespaceURI == WORD && element.name.localPart == "font") {
                        val family = element.attribute(WORD, "name") ?: invalid("Embedded font family is missing")
                        for (face in element.elements().filter { it.name.namespaceURI == WORD && it.name.localPart in setOf("embedRegular", "embedBold", "embedItalic", "embedBoldItalic") }) {
                            val target = relationships[face.attribute(OFFICE, "id")] ?: invalid("Embedded font relationship is missing")
                            val name = java.nio.file.Path.of("word").resolve(target).normalize().toString().replace('\\', '/').removePrefix("/")
                            if (!name.startsWith("word/fonts/")) unsupported("Embedded fonts must remain inside the font package directory")
                            val bytes = read(zip, name)
                            embeddedBytes += bytes.size
                            if (embeddedBytes > minOf(64L * 1024 * 1024, context.limits.maxExpandedBytes)) PdfFailure.fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, "Embedded PDF font budget was exceeded")
                            val key = face.attribute(WORD, "fontKey") ?: invalid("Embedded font key is missing")
                            val guid = "[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}"
                            if (!Regex("(?:$guid|\\{$guid\\})").matches(key) || bytes.size < 32) invalid("Embedded font key is invalid")
                            val hex = key.replace(Regex("[{}-]"), "")
                            val mask = (0..15).map { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }.reversed()
                            repeat(32) { bytes[it] = (bytes[it].toInt() xor mask[it % 16].toInt()).toByte() }
                            val metadata = PdfFontMetadata.read(bytes)
                            if (family !in metadata.families) invalid("Embedded font family does not match its metadata")
                            val expected = when (face.name.localPart) { "embedBold" -> "bold"; "embedItalic" -> "italic"; "embedBoldItalic" -> "boldItalic"; else -> "regular" }
                            if (metadata.face != expected) invalid("Embedded font face does not match its metadata")
                            metadata.requirePrintEmbedding()
                            if (metadata.face == "regular") embedded.addAll(metadata.families)
                        }
                    }
                    if (required.size > 128 || required.any { it.isBlank() || it.length > 128 || it.any(Char::isISOControl) }) invalid("Invalid or excessive PDF font declarations")
                }
            }
        }
        if (!ascii || !ansi) unsupported("PDF font preservation requires explicit ascii and hAnsi document defaults")
        return required to embedded
    }

    fun ensureAvailable(required: Set<String>, available: Set<String>) {
        if (!available.map { it.lowercase(Locale.ROOT) }.toSet().containsAll(required.map { it.lowercase(Locale.ROOT) })) {
            PdfFailure.fail(DiagnosticCodes.INVALID_REQUEST, "A selected document font is unavailable; supply it as a font resource or embed it in the template")
        }
    }

    private fun invalid(message: String): Nothing = PdfFailure.fail(DiagnosticCodes.INVALID_REQUEST, message)
    private fun unsupported(message: String): Nothing = PdfFailure.fail(DiagnosticCodes.UNSUPPORTED_INPUT, message)
}
