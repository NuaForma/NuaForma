package dev.nuaforma.docx.adapters.docx4j

import dev.nuaforma.contracts.RejectionReason
import dev.nuaforma.contracts.Diagnostic
import dev.nuaforma.contracts.GenerationException
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
                    if (index >= context.limits.maxArchiveEntries) limit(RejectionReason.DOCX_ARCHIVE_ENTRY_BUDGET_WAS_EXCEEDED)
                    entry
                }.toList()
                val names = entries.map { it.name }.toSet()
                if (names.size != entries.size) invalid(RejectionReason.DOCX_CONTAINS_DUPLICATE_ARCHIVE_ENTRIES)
                entries.forEach { entry ->
                    val name = entry.name.removeSuffix("/")
                    if (name.isEmpty() || name.startsWith('/') || name.contains('\\') || name.contains(':') || name.contains('%') ||
                        name.split('/').any { it.isEmpty() || it == "." || it == ".." }) invalid(RejectionReason.DOCX_CONTAINS_AN_UNSAFE_ARCHIVE_PATH)
                    if (name.startsWith("_xmlsignatures/") || name.contains("vbaProject", true) || name.startsWith("word/embeddings/") ||
                        name.startsWith("word/activeX/")) unsupported(RejectionReason.MACROS_EMBEDDED_OBJECTS_AND_SIGNED_PACKAGES_ARE_NOT_SUPPORTED)
                }
                if (!names.containsAll(setOf("[Content_Types].xml", "_rels/.rels", "word/document.xml"))) {
                    invalid(RejectionReason.DOCX_NEEDS_CONTENT_TYPES_PACKAGE_RELATIONSHIPS_AND_WORD_DOCUMENT)
                }
                val overrides = mutableMapOf<String, String>()
                val defaults = mutableMapOf<String, String>()
                // This metadata pass has its own byte bound; the complete pass below counts all expanded bytes.
                zip.getInputStream(zip.getEntry("[Content_Types].xml")).use { stream ->
                    xml(CheckedInput(stream, context.limits.maxExpandedBytes, context), "[Content_Types].xml") { event ->
                        if (event.depth == 1 && (event.local != "Types" || event.namespace != CONTENT_TYPES)) invalid(RejectionReason.INVALID_DOCX_CONTENT_TYPES_ROOT)
                        if (event.local == "Override" && event.namespace == CONTENT_TYPES) {
                            val name = event.attributes["PartName"] ?: invalid(RejectionReason.MISSING_CONTENT_TYPE_PART_NAME)
                            val type = event.attributes["ContentType"] ?: invalid(RejectionReason.MISSING_CONTENT_TYPE)
                            if (!name.startsWith('/') || overrides.put(name.removePrefix("/"), type) != null) invalid(RejectionReason.INVALID_OR_DUPLICATE_CONTENT_TYPE_OVERRIDE)
                        }
                        if (event.local == "Default" && event.namespace == CONTENT_TYPES) {
                            val extension = event.attributes["Extension"] ?: invalid(RejectionReason.MISSING_CONTENT_TYPE_EXTENSION)
                            val type = event.attributes["ContentType"] ?: invalid(RejectionReason.MISSING_CONTENT_TYPE)
                            if (defaults.put(extension, type) != null) invalid(RejectionReason.DUPLICATE_DEFAULT_CONTENT_TYPE)
                        }
                    }
                }
                if (overrides["word/document.xml"] != MAIN_TYPE) unsupported(RejectionReason.ONLY_TRANSITIONAL_DOCX_TEXT_DOCUMENTS_ARE_SUPPORTED_CONVERT_THIS)
                overrides.keys.forEach { if (it !in names) invalid(RejectionReason.CONTENT_TYPE_POINTS_TO_A_MISSING_PACKAGE_PART) }
                var expanded = 0L
                var officeDocuments = 0
                entries.filterNot { it.isDirectory }.forEach { entry ->
                    context.checkActive()
                    val type = overrides[entry.name] ?: defaults[entry.name.substringAfterLast('.', "")]
                    if (entry.name != "[Content_Types].xml" && type == null) invalid(RejectionReason.A_DOCX_PART_HAS_NO_CONTENT_TYPE)
                    if (type != null && (type.contains("macro", true) || type.contains("vba", true) || type.contains("oleObject", true) ||
                            type.contains("activeX", true) || type.contains("html", true))) unsupported(RejectionReason.ACTIVE_CONTENT_AND_EMBEDDED_DOCUMENTS_ARE_NOT_SUPPORTED)
                    zip.getInputStream(entry).use { source ->
                        val stream = CheckedInput(source, context.limits.maxExpandedBytes - expanded, context)
                        if (entry.name.endsWith(".xml") || entry.name.endsWith(".rels") || type?.endsWith("+xml") == true || type == "application/xml") {
                            val ids = mutableSetOf<String>()
                            xml(stream, entry.name) { event ->
                                if (entry.name == "word/document.xml" && event.depth == 1 &&
                                    (event.local != "document" || event.namespace != WORD)) unsupported(RejectionReason.ONLY_TRANSITIONAL_WORDPROCESSINGML_IS_SUPPORTED)
                                if (entry.name.endsWith(".rels")) {
                                    if (event.depth == 1 && (event.local != "Relationships" || event.namespace != RELATIONSHIPS)) invalid(RejectionReason.INVALID_RELATIONSHIP_ROOT)
                                    if (event.local == "Relationship" && event.namespace == RELATIONSHIPS) {
                                        val id = event.attributes["Id"] ?: invalid(RejectionReason.MISSING_RELATIONSHIP_ID)
                                        if (id.isBlank()) invalid(RejectionReason.EMPTY_RELATIONSHIP_ID)
                                        if (!ids.add(id)) invalid(RejectionReason.DUPLICATE_RELATIONSHIP_ID)
                                        val target = event.attributes["Target"] ?: invalid(RejectionReason.MISSING_RELATIONSHIP_TARGET)
                                        val relationship = event.attributes["Type"] ?: invalid(RejectionReason.MISSING_RELATIONSHIP_TYPE)
                                        if (relationship.endsWith("/attachedTemplate")) unsupported(RejectionReason.LINKED_TEMPLATE_RELATIONSHIP)
                                        if (relationship.endsWith("/aFChunk") || relationship.endsWith("/oleObject")) {
                                            unsupported(RejectionReason.LINKED_TEMPLATES_AND_EMBEDDED_DOCUMENTS_ARE_NOT_SUPPORTED)
                                        }
                                        when (event.attributes["TargetMode"]) {
                                            "External" -> if (relationship != OFFICE + "hyperlink") unsupported(RejectionReason.EXTERNAL_RELATIONSHIPS_OTHER_THAN_PASSIVE_HYPERLINKS_ARE_NOT_SUPPORTED)
                                            null, "Internal" -> {
                                                val resolved = resolve(entry.name, target)
                                                if (resolved !in names) invalid(RejectionReason.A_DOCX_RELATIONSHIP_POINTS_TO_A_MISSING_PACKAGE_PART)
                                                if (entry.name == "_rels/.rels" && relationship == OFFICE + "officeDocument") {
                                                    officeDocuments++
                                                    if (resolved != "word/document.xml") unsupported(RejectionReason.DOCX_MAIN_DOCUMENT_MUST_BE_WORD_DOCUMENT_XML)
                                                }
                                            }
                                            else -> invalid(RejectionReason.INVALID_RELATIONSHIP_TARGET_MODE)
                                        }
                                    }
                                }
                            }
                        } else {
                            val buffer = ByteArray(8192)
                            while (stream.read(buffer) >= 0) { /* Validate bounded opaque bytes and CRC. */ }
                        }
                        if (stream.count != entry.size || stream.crc.value != entry.crc) invalid(RejectionReason.DOCX_ARCHIVE_INTEGRITY_CHECK_FAILED)
                        expanded += stream.count
                    }
                }
                if (officeDocuments != 1) invalid(RejectionReason.DOCX_MUST_HAVE_EXACTLY_ONE_INTERNAL_OFFICE_DOCUMENT_RELATIONSHIP)
            }
        } catch (_: ZipException) {
            invalid(RejectionReason.INPUT_IS_NOT_A_VALID_UNENCRYPTED_DOCX_ZIP_ARCHIVE)
        } catch (_: XMLStreamException) {
            invalid(RejectionReason.A_DOCX_XML_PART_IS_MALFORMED_OR_CONTAINS_PROHIBITED)
        }
    }

    private fun resolve(relationshipPart: String, target: String): String {
        try {
            if (target.contains('\\') || target.contains('%')) invalid(RejectionReason.INVALID_INTERNAL_RELATIONSHIP_TARGET)
            val base = if (relationshipPart == "_rels/.rels") "/" else {
                val source = relationshipPart.replace("/_rels/", "/").removeSuffix(".rels")
                source.substringBeforeLast('/') + "/"
            }
            val uri = URI("/" + base.removePrefix("/")).resolve(URI(target)).normalize()
            if (uri.isAbsolute || uri.rawAuthority != null || uri.rawQuery != null || uri.rawFragment != null || uri.path.split('/').any { it == ".." }) {
                invalid(RejectionReason.INVALID_INTERNAL_RELATIONSHIP_TARGET)
            }
            return uri.path.removePrefix("/")
        } catch (_: IllegalArgumentException) {
            invalid(RejectionReason.INVALID_INTERNAL_RELATIONSHIP_TARGET)
        } catch (_: java.net.URISyntaxException) {
            invalid(RejectionReason.INVALID_INTERNAL_RELATIONSHIP_TARGET)
        }
    }

    private class Event(val local: String, val namespace: String?, val depth: Int, val attributes: Map<String, String>)

    private fun xml(stream: InputStream, part: String, inspect: (Event) -> Unit) {
        val factory = XMLInputFactory.newDefaultFactory()
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false)
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        factory.setXMLResolver { _, _, _, _ -> throw XMLStreamException("External XML resolution is disabled") }
        val reader = try { factory.createXMLStreamReader(stream) } catch (error: XMLStreamException) { malformed(part, error) }
        var depth = 0
        try {
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.DTD, XMLStreamConstants.ENTITY_REFERENCE -> invalid(RejectionReason.DOCX_XML_ENTITIES_AND_DTDS_ARE_PROHIBITED)
                    XMLStreamConstants.START_ELEMENT -> {
                        depth++
                        if (depth > 256 || reader.attributeCount > 256) limit(RejectionReason.DOCX_XML_COMPLEXITY_BUDGET_WAS_EXCEEDED)
                        inspect(Event(reader.localName, reader.namespaceURI, depth, (0 until reader.attributeCount)
                            .filter { reader.getAttributeNamespace(it).isNullOrEmpty() }
                            .associate { reader.getAttributeLocalName(it) to reader.getAttributeValue(it) }))
                    }
                    XMLStreamConstants.END_ELEMENT -> depth--
                }
            }
        } catch (error: XMLStreamException) {
            malformed(part, error)
        } catch (error: GenerationException) {
            throw GenerationException(error.diagnostics.map { diagnostic ->
                diagnostic.rejectionReason?.let { Diagnostic(diagnostic.code, it, diagnostic.severity,
                    "xml:${reader.location.lineNumber.coerceAtLeast(0)}:${reader.location.columnNumber.coerceAtLeast(0)}", part) } ?: diagnostic
            })
        } finally { reader.close() }
    }

    private fun malformed(part: String, error: XMLStreamException): Nothing {
        val location = error.location?.let { "xml:${it.lineNumber.coerceAtLeast(0)}:${it.columnNumber.coerceAtLeast(0)}" }
        throw GenerationException(listOf(Diagnostic(DiagnosticCodes.INVALID_TEMPLATE,
            RejectionReason.A_DOCX_XML_PART_IS_MALFORMED_OR_CONTAINS_PROHIBITED, location = location, part = part)))
    }

    private class CheckedInput(input: InputStream, private val limit: Long, private val context: GenerationContext) : FilterInputStream(input) {
        var count = 0L
        val crc = CRC32()
        override fun read(): Int {
            context.checkActive()
            val value = super.read()
            if (value >= 0) {
                if (count >= limit) limit(RejectionReason.DOCX_EXPANDED_BYTE_BUDGET_WAS_EXCEEDED)
                count++
                crc.update(value)
            }
            return value
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            context.checkActive()
            val size = super.read(bytes, offset, length)
            if (size > 0) {
                if (size > limit - count) limit(RejectionReason.DOCX_EXPANDED_BYTE_BUDGET_WAS_EXCEEDED)
                count += size
                crc.update(bytes, offset, size)
            }
            return size
        }
    }

    private fun invalid(reason: RejectionReason): Nothing = DocxFailure.fail(DiagnosticCodes.INVALID_TEMPLATE, reason)
    private fun unsupported(reason: RejectionReason): Nothing = DocxFailure.fail(DiagnosticCodes.UNSUPPORTED_INPUT, reason)
    private fun limit(reason: RejectionReason): Nothing = DocxFailure.fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, reason)
}
