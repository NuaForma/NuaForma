package dev.nuaforma.docx.adapters.docx4j

import dev.nuaforma.contracts.DiagnosticCodes
import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.internal.DocxFailure
import java.io.FilterInputStream
import java.io.InputStream
import java.net.URI
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.ZipException
import java.util.zip.ZipFile
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException

/** Validate original bytes before docx4j sees them; never extract archive paths or resolve URLs. */
internal object PackageValidation {
    private const val CONTENT_TYPES = "http://schemas.openxmlformats.org/package/2006/content-types"
    private const val RELATIONSHIPS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val OFFICE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/"
    private const val WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val MAIN_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"

    fun validate(path: Path, context: GenerationContext) {
        context.checkActive()
        try {
            ZipFile(path.toFile()).use { zip ->
                val entries = zip.entries().asSequence().mapIndexed { index, entry ->
                    context.checkActive()
                    if (index >= context.limits.maxArchiveEntries) limit("DOCX archive entry budget was exceeded")
                    entry
                }.toList()
                val names = entries.map { it.name }.toSet()
                if (names.size != entries.size) invalid("DOCX contains duplicate archive entries")
                entries.forEach { entry ->
                    val name = entry.name.removeSuffix("/")
                    if (name.isEmpty() || name.startsWith('/') || name.contains('\\') || name.contains(':') || name.contains('%') ||
                        name.split('/').any { it.isEmpty() || it == "." || it == ".." }) invalid("DOCX contains an unsafe archive path")
                    if (name.startsWith("_xmlsignatures/") || name.contains("vbaProject", true) || name.startsWith("word/embeddings/") ||
                        name.startsWith("word/activeX/")) unsupported("Macros, embedded objects and signed packages are not supported")
                }
                if (!names.containsAll(setOf("[Content_Types].xml", "_rels/.rels", "word/document.xml"))) {
                    invalid("DOCX needs content types, package relationships and word/document.xml")
                }
                val overrides = mutableMapOf<String, String>()
                val defaults = mutableMapOf<String, String>()
                // This metadata pass has its own byte bound; the complete pass below counts all expanded bytes.
                zip.getInputStream(zip.getEntry("[Content_Types].xml")).use { stream ->
                    xml(CheckedInput(stream, context.limits.maxExpandedBytes, context)) { event ->
                        if (event.depth == 1 && (event.local != "Types" || event.namespace != CONTENT_TYPES)) invalid("Invalid DOCX content types root")
                        if (event.local == "Override" && event.namespace == CONTENT_TYPES) {
                            val name = event.attributes["PartName"] ?: invalid("Missing content type part name")
                            val type = event.attributes["ContentType"] ?: invalid("Missing content type")
                            if (!name.startsWith('/') || overrides.put(name.removePrefix("/"), type) != null) invalid("Invalid or duplicate content type override")
                        }
                        if (event.local == "Default" && event.namespace == CONTENT_TYPES) {
                            val extension = event.attributes["Extension"] ?: invalid("Missing content type extension")
                            val type = event.attributes["ContentType"] ?: invalid("Missing content type")
                            if (defaults.put(extension, type) != null) invalid("Duplicate default content type")
                        }
                    }
                }
                if (overrides["word/document.xml"] != MAIN_TYPE) unsupported("Only transitional DOCX text documents are supported; convert this file to DOCX first")
                overrides.keys.forEach { if (it !in names) invalid("Content type points to a missing package part") }
                var expanded = 0L
                var officeDocuments = 0
                entries.filterNot { it.isDirectory }.forEach { entry ->
                    context.checkActive()
                    val type = overrides[entry.name] ?: defaults[entry.name.substringAfterLast('.', "")]
                    if (entry.name != "[Content_Types].xml" && type == null) invalid("A DOCX part has no content type")
                    if (type != null && (type.contains("macro", true) || type.contains("vba", true) || type.contains("oleObject", true) ||
                            type.contains("activeX", true) || type.contains("html", true))) unsupported("Active content and embedded documents are not supported")
                    zip.getInputStream(entry).use { source ->
                        val stream = CheckedInput(source, context.limits.maxExpandedBytes - expanded, context)
                        if (entry.name.endsWith(".xml") || entry.name.endsWith(".rels") || type?.endsWith("+xml") == true || type == "application/xml") {
                            val ids = mutableSetOf<String>()
                            xml(stream) { event ->
                                if (entry.name == "word/document.xml" && event.depth == 1 &&
                                    (event.local != "document" || event.namespace != WORD)) unsupported("Only transitional WordprocessingML is supported")
                                if (entry.name.endsWith(".rels")) {
                                    if (event.depth == 1 && (event.local != "Relationships" || event.namespace != RELATIONSHIPS)) invalid("Invalid relationship root")
                                    if (event.local == "Relationship" && event.namespace == RELATIONSHIPS) {
                                        val id = event.attributes["Id"] ?: invalid("Missing relationship ID")
                                        if (id.isBlank()) invalid("Empty relationship ID")
                                        if (!ids.add(id)) invalid("Duplicate relationship ID")
                                        val target = event.attributes["Target"] ?: invalid("Missing relationship target")
                                        val relationship = event.attributes["Type"] ?: invalid("Missing relationship type")
                                        if (relationship.endsWith("/aFChunk") || relationship.endsWith("/oleObject") || relationship.endsWith("/attachedTemplate")) {
                                            unsupported("Linked templates and embedded documents are not supported")
                                        }
                                        when (event.attributes["TargetMode"]) {
                                            "External" -> if (relationship != OFFICE + "hyperlink") unsupported("External relationships other than passive hyperlinks are not supported")
                                            null, "Internal" -> {
                                                val resolved = resolve(entry.name, target)
                                                if (resolved !in names) invalid("A DOCX relationship points to a missing package part")
                                                if (entry.name == "_rels/.rels" && relationship == OFFICE + "officeDocument") {
                                                    officeDocuments++
                                                    if (resolved != "word/document.xml") unsupported("DOCX main document must be word/document.xml")
                                                }
                                            }
                                            else -> invalid("Invalid relationship target mode")
                                        }
                                    }
                                }
                            }
                        } else {
                            val buffer = ByteArray(8192)
                            while (stream.read(buffer) >= 0) { /* Validate bounded opaque bytes and CRC. */ }
                        }
                        if (stream.count != entry.size || stream.crc.value != entry.crc) invalid("DOCX archive integrity check failed")
                        expanded += stream.count
                    }
                }
                if (officeDocuments != 1) invalid("DOCX must have exactly one internal office document relationship")
            }
        } catch (_: ZipException) {
            invalid("Input is not a valid unencrypted DOCX ZIP archive")
        } catch (_: XMLStreamException) {
            invalid("A DOCX XML part is malformed or contains prohibited entities; repair the document")
        }
    }

    private fun resolve(relationshipPart: String, target: String): String {
        try {
            if (target.contains('\\') || target.contains('%')) invalid("Invalid internal relationship target")
            val base = if (relationshipPart == "_rels/.rels") "/" else {
                val source = relationshipPart.replace("/_rels/", "/").removeSuffix(".rels")
                source.substringBeforeLast('/') + "/"
            }
            val uri = URI("/" + base.removePrefix("/")).resolve(URI(target)).normalize()
            if (uri.isAbsolute || uri.rawAuthority != null || uri.rawQuery != null || uri.rawFragment != null || uri.path.split('/').any { it == ".." }) {
                invalid("Invalid internal relationship target")
            }
            return uri.path.removePrefix("/")
        } catch (_: IllegalArgumentException) {
            invalid("Invalid internal relationship target")
        } catch (_: java.net.URISyntaxException) {
            invalid("Invalid internal relationship target")
        }
    }

    private class Event(val local: String, val namespace: String?, val depth: Int, val attributes: Map<String, String>)

    private fun xml(stream: InputStream, inspect: (Event) -> Unit) {
        val factory = XMLInputFactory.newDefaultFactory()
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false)
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        factory.setXMLResolver { _, _, _, _ -> throw XMLStreamException("External XML resolution is disabled") }
        val reader = factory.createXMLStreamReader(stream)
        var depth = 0
        try {
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.DTD, XMLStreamConstants.ENTITY_REFERENCE -> invalid("DOCX XML entities and DTDs are prohibited")
                    XMLStreamConstants.START_ELEMENT -> {
                        depth++
                        if (depth > 256 || reader.attributeCount > 256) limit("DOCX XML complexity budget was exceeded")
                        inspect(Event(reader.localName, reader.namespaceURI, depth, (0 until reader.attributeCount)
                            .filter { reader.getAttributeNamespace(it).isNullOrEmpty() }
                            .associate { reader.getAttributeLocalName(it) to reader.getAttributeValue(it) }))
                    }
                    XMLStreamConstants.END_ELEMENT -> depth--
                }
            }
        } finally { reader.close() }
    }

    private class CheckedInput(input: InputStream, private val limit: Long, private val context: GenerationContext) : FilterInputStream(input) {
        var count = 0L
        val crc = CRC32()
        override fun read(): Int {
            context.checkActive()
            val value = super.read()
            if (value >= 0) {
                if (count >= limit) limit("DOCX expanded byte budget was exceeded")
                count++
                crc.update(value)
            }
            return value
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            context.checkActive()
            val size = super.read(bytes, offset, length)
            if (size > 0) {
                if (size > limit - count) limit("DOCX expanded byte budget was exceeded")
                count += size
                crc.update(bytes, offset, size)
            }
            return size
        }
    }

    private fun invalid(message: String): Nothing = DocxFailure.fail(DiagnosticCodes.INVALID_TEMPLATE, message)
    private fun unsupported(message: String): Nothing = DocxFailure.fail(DiagnosticCodes.UNSUPPORTED_INPUT, message)
    private fun limit(message: String): Nothing = DocxFailure.fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, message)
}
