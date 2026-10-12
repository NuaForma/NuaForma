package dev.nuaforma.docx

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.internal.OpenTypeMetadata
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale

/** Bounded, immutable startup snapshot; no native registration or subsequent mount reads. */
class DeploymentFonts private constructor(private val entries: Map<String, ByteArray>, val catalog: FontCatalog) {
    fun bind(request: GenerationRequest): GenerationRequest {
        val declarations = request.requirements.output.options["fonts"] as? List<*> ?: return request
        val resources = request.resources.toMutableMap()
        for (raw in declarations) {
            val id = (raw as? Map<*, *>)?.get("resource") as? String ?: continue
            if (id !in resources) entries[id]?.let { bytes -> resources[id] = ResourceSource { bytes.inputStream() } }
        }
        return GenerationRequest(request.template, request.requirements, request.data, resources)
    }

    /** Selected missing family/face resources for the private PDF conversion, never the whole mount. */
    fun sourcesFor(families: Set<String>, excluded: Set<String>): Map<String, ResourceSource> {
        val wanted = families.map { it.lowercase(Locale.ROOT) }.toSet() - excluded.map { it.lowercase(Locale.ROOT) }.toSet()
        return catalog.fonts.filter { it.family.lowercase(Locale.ROOT) in wanted }.mapNotNull { it.resource }.distinct()
            .associateWith { id -> ResourceSource { entries.getValue(id).inputStream() } }
    }

    fun declarations(ids: Set<String>): List<Map<String, String>> = catalog.fonts.filter { it.resource in ids }
        .distinctBy { it.resource }.map { mapOf("family" to it.family, "face" to it.face, "resource" to it.resource!!) }

    companion object {
        @JvmStatic fun configured(): DeploymentFonts = System.getProperty("nuaforma.fonts.directory")?.let { load(Path.of(it)) }
            ?: DeploymentFonts(emptyMap(), FontCatalog(emptyList()))

        @JvmStatic fun load(directory: Path): DeploymentFonts {
            require(directory.isAbsolute) { "Configure an absolute font mount directory" }
            if (!Files.exists(directory, NOFOLLOW_LINKS)) return DeploymentFonts(emptyMap(), FontCatalog(emptyList()))
            require(Files.isDirectory(directory, NOFOLLOW_LINKS)) { "Font mount must be a directory without symlinks" }
            val paths = Files.list(directory).use { it.limit(257).toList() }
            require(paths.size <= 256) { "Font directory entry limit exceeded" }
            val files = paths.filter { it.fileName.toString().substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("ttf", "otf", "ttc", "woff", "woff2") }
            require(files.size <= 64) { "Font file limit exceeded" }
            val entries = linkedMapOf<String, ByteArray>()
            val fonts = mutableListOf<AvailableFont>()
            val identities = mutableMapOf<Pair<String, String>, String>()
            var total = 0L
            for (path in files.sortedBy { it.fileName.toString() }) {
                require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Font files must be regular files without symlinks" }
                val bytes = Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(16 * 1024 * 1024 + 1) }
                total += bytes.size
                require(bytes.size <= 16 * 1024 * 1024 && total <= 64L * 1024 * 1024) { "Font byte limit exceeded" }
                val metadata = OpenTypeMetadata.read(bytes)
                val id = "nf-mounted-" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                entries[id] = bytes
                for (family in metadata.families.sorted()) {
                    val key = family.lowercase(Locale.ROOT) to metadata.face
                    require(identities[key] == null || identities[key] == id) { "Conflicting mounted font family/face" }
                    if (identities.put(key, id) == null) fonts.add(AvailableFont(family, metadata.face, FontOrigin.MOUNTED, id))
                }
            }
            return DeploymentFonts(entries.toMap(), FontCatalog(fonts))
        }
    }
}
