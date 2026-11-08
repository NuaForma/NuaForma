package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.adapters.ooxml.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Request-local OPC editing. Retained entry bytes are unchanged unless their XML is edited. */
internal class CleanupPackage(private val original: ByteArray, private val context: GenerationContext) {
    val changes = mutableListOf<TemplateCleanupChange>()
    val parts = linkedMapOf<String, ByteArray>()
    private val parsed = mutableMapOf<String, XmlElement>()
    private val edited = mutableSetOf<String>()
    private val xml = ConversionXml(context)
    private var changed = false
    private val relations = "http://schemas.openxmlformats.org/package/2006/relationships"
    private var entryCount = 0
    private var expandedBytes = 0L

    init {
        budget(original.size <= minOf(8_388_608L, context.limits.maxTemplateBytes))
        readArchive()
        require(setOf("[Content_Types].xml", "_rels/.rels", "word/document.xml").all(parts::containsKey)) { "Missing DOCX package structure" }
    }

    private fun readArchive() {
        val seen = mutableSetOf<String>()
        val directory = Files.createTempDirectory("nuaforma-cleanup-")
        val source = directory.resolve("source.docx")
        try {
            Files.write(source, original)
            ZipFile(source.toFile()).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    context.checkActive()
                    val entry = entries.nextElement()
                    zip.getInputStream(entry).use { readEntry(it, entry, seen) }
                }
            }
            if (!streamMatchesDirectory()) record(TemplateCleanupAction.RESTORE_REQUIRED_STRUCTURE, "[package]", null,
                "Normalized inconsistent ZIP stream/directory views using the Word package directory")
        } catch (_: ZipException) { throw IllegalArgumentException("Invalid DOCX archive") }
        finally {
            try { Files.deleteIfExists(source) } finally { Files.deleteIfExists(directory) }
        }
    }

    private fun readEntry(input: InputStream, entry: ZipEntry, seen: MutableSet<String>) {
        budget(++entryCount <= minOf(64, context.limits.maxArchiveEntries))
        val name = entry.name.removeSuffix("/")
        require(validName(name)) { "Unsafe package entry name" }
        require(seen.add(name)) { "Duplicate package entry" }
        val bytes = readEntryBytes(input)
        require(bytes.size.toLong() == entry.size && CRC32().apply { update(bytes) }.value == entry.crc) { "Invalid ZIP member checksum or size" }
        if (entry.isDirectory) record(TemplateCleanupAction.REMOVE_PART, entry.name, null, "Removed an unneeded ZIP directory entry")
        else parts[name] = bytes
    }

    private fun validName(name: String): Boolean = name.length in 1..255 && name.split('/').all { segment ->
        segment.isNotEmpty() && segment !in setOf(".", "..") && segment == segment.trim() &&
            segment.all { it.isLetterOrDigit() || it in " ._[]()-" }
    }

    private fun readEntryBytes(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            context.checkActive()
            val length = input.read(buffer)
            if (length < 0) break
            expandedBytes += length
            budget(expandedBytes <= minOf(8_388_608L, context.limits.maxExpandedBytes) && output.size() + length <= 4_194_304)
            output.write(buffer, 0, length)
        }
        return output.toByteArray()
    }

    private fun streamMatchesDirectory(): Boolean {
        val seen = mutableSetOf<String>()
        try {
            ZipInputStream(original.inputStream()).use { zip ->
                while (true) {
                    context.checkActive()
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory || !seen.add(entry.name)) return false
                    val expected = parts[entry.name] ?: return false
                    val buffer = ByteArray(8192)
                    var offset = 0
                    while (true) {
                        context.checkActive()
                        val length = zip.read(buffer)
                        if (length < 0) break
                        if (length > expected.size - offset) return false
                        for (index in 0 until length) if (buffer[index] != expected[offset + index]) return false
                        offset += length
                    }
                    if (offset != expected.size) return false
                    zip.closeEntry()
                }
            }
        } catch (_: ZipException) { return false }
        return seen == parts.keys
    }

    fun record(action: TemplateCleanupAction, part: String, path: String?, reason: String) {
        budget(changes.size < 2048)
        changes += TemplateCleanupChange(action, part, path, reason.take(256))
        changed = true
    }

    fun root(part: String): XmlElement? = parts[part]?.let { parsed.getOrPut(part) { xml.readPart(it, part) } }

    fun touch(part: String) {
        edited += part
        changed = true
    }

    fun unlink(part: String, parent: XmlElement, node: XmlElement, location: String?, reason: String) {
        if (!parent.children.remove(node)) return
        touch(part)
        record(TemplateCleanupAction.REMOVE_ELEMENT, part, location, reason)
        if (part.endsWith(".rels")) node.attribute("", "Id")?.let { removeReferences(owner(part), it) }
    }

    fun replace(part: String, content: String, reason: String) {
        parts[part] = content.toByteArray(Charsets.UTF_8)
        parsed.remove(part)
        edited.remove(part)
        record(TemplateCleanupAction.RESTORE_REQUIRED_STRUCTURE, part, null, reason)
    }

    fun remove(part: String, reason: String) {
        if (parts.remove(part) == null) return
        parsed.remove(part)
        edited.remove(part)
        record(TemplateCleanupAction.REMOVE_PART, part, null, reason)
        relationshipPart(part)?.let { remove(it, "Removed relationships belonging to a removed part") }
    }

    fun bytes(): ByteArray {
        if (!changed) return original
        for (name in edited) parsed[name]?.let { parts[name] = xml.write(it) }
        edited.clear()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            parts.forEach { (name, bytes) ->
                context.checkActive()
                zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                zip.write(bytes)
                zip.closeEntry()
                budget(out.size() <= minOf(8_388_608L, context.limits.maxTemplateBytes))
            }
        }
        budget(out.size() <= minOf(8_388_608L, context.limits.maxTemplateBytes))
        return out.toByteArray()
    }

    fun repairReferences() {
        for (name in parts.keys.filter { it.endsWith(".rels") }.toList()) repairRelationshipPart(name)
        repairContentTypes()
    }

    private fun repairRelationshipPart(name: String) {
        val owner = owner(name)
        if (owner != null && owner !in parts) {
            remove(name, "Removed relationships whose owner no longer exists")
            return
        }
        val root = root(name) ?: return
        if (root.name.namespaceURI != relations || root.name.localPart != "Relationships") return
        for ((index, relation) in root.elements().withIndex()) {
            val target = relation.attribute("", "Target")
            if (relation.attribute("", "TargetMode") == "External") continue
            if (target == null || resolve(owner, target) !in parts)
                unlink(name, root, relation, "/Relationships/Relationship[${index + 1}]", "Removed a missing or invalid internal relationship")
        }
    }

    private fun repairContentTypes() {
        val types = root("[Content_Types].xml") ?: return
        for (node in types.elements().filter { it.name.localPart == "Override" }) {
            val target = node.attribute("", "PartName")?.let { resolve(null, it) }
            if (target !in parts) {
                types.children.remove(node)
                touch("[Content_Types].xml")
                record(TemplateCleanupAction.REMOVE_ELEMENT, "[Content_Types].xml", "/Types/Override", "Removed a content-type declaration for a removed part")
            }
        }
    }

    private fun removeReferences(owner: String?, id: String) {
        if (owner == null) return
        val root = root(owner) ?: return
        val namespace = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        fun visit(node: XmlElement, ancestors: List<XmlElement>) {
            context.checkActive()
            for (child in node.elements().toList()) {
                if (child.attributes.any { (key, value) -> key.namespaceURI == namespace && value == id }) {
                    val chain = ancestors + node + child
                    val objectNode = chain.lastOrNull { it.name.namespaceURI == WORD && it.name.localPart in setOf(
                        "drawing", "pict", "object", "hyperlink", "altChunk", "subDoc", "control") } ?: child
                    val parent = chain.getOrNull(chain.indexOf(objectNode) - 1) ?: node
                    if (parent.children.remove(objectNode)) {
                        touch(owner)
                        record(TemplateCleanupAction.REMOVE_ELEMENT, owner, "/${objectNode.name.localPart}", "Removed an object or reference whose relationship was removed")
                    }
                } else visit(child, ancestors + node)
            }
        }
        visit(root, emptyList())
    }

    private fun owner(part: String): String? {
        if (part == "_rels/.rels") return null
        val index = part.lastIndexOf("/_rels/")
        return if (index < 0) "[missing relationship owner]" else part.substring(0, index + 1) + part.substring(index + 7).removeSuffix(".rels")
    }

    private fun relationshipPart(part: String): String? {
        if (part.endsWith(".rels")) return null
        val index = part.lastIndexOf('/')
        return if (index < 0) "_rels/$part.rels" else part.substring(0, index + 1) + "_rels/" + part.substring(index + 1) + ".rels"
    }

    private fun resolve(owner: String?, target: String): String? = try {
        val relative = URI(target)
        if (relative.isAbsolute || relative.rawAuthority != null) null else {
            val result = URI("https://package.invalid/" + owner.orEmpty()).resolve(relative).normalize()
            result.path?.removePrefix("/")?.takeIf { result.rawQuery == null && result.rawFragment == null }
        }
    } catch (_: IllegalArgumentException) { null } catch (_: java.net.URISyntaxException) { null }

    private fun budget(valid: Boolean) {
        if (!valid) throw GenerationException(listOf(Diagnostic(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED,
            RejectionReason.PDF_CONVERSION_BYTE_BUDGET_WAS_EXCEEDED)))
    }

    companion object {
        const val WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    }
}
