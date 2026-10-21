package dev.nuaforma.docx

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.internal.OpenTypeMetadata
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale

/** Bounded, immutable startup snapshot; no native registration or subsequent mount reads. */
class DeploymentFonts private constructor(private val entries: Map<String, ByteArray>, val catalog: FontCatalog,
    private val failures: List<FileFailure> = emptyList(), internal val conflicts: Set<Triple<String, String, FontOrigin>> = emptySet()) {
    private var reported = false

    /** Report the captured startup failures once, when an explicit logging scope becomes available. */
    @Synchronized
    fun reportFailures(logging: GenerationLogging) {
        if (!logging.enabled || reported) return
        reported = true
        for (failure in failures) {
            val fields = linkedMapOf("file" to failure.file, "result" to "Font loading failed; file excluded from startup snapshot")
            failure.conflict?.let { fields["conflictingFile"] = it }
            logging.operation("MOUNTED_FONT_REJECTED", fields)
            if (logging.mode == LoggingMode.DEVELOPMENT) {
                val causes = mutableListOf<Map<String, Any>>()
                var cause: Throwable? = failure.error
                repeat(4) {
                    val current = cause ?: return@repeat
                    causes.add(mapOf("exception" to current.javaClass.name, "stack" to current.stackTrace.take(4).map {
                        "${it.className}.${it.methodName}:${it.lineNumber}"
                    }))
                    cause = current.cause
                }
                logging.trace("MOUNTED_FONT_REJECTION_DETAIL", fields + mapOf("reason" to failure.reason, "causes" to causes))
            }
        }
    }

    private class FileFailure(val file: String, val reason: String, val error: Exception, val conflict: String? = null)
    private class Candidate(val file: String, val bytes: ByteArray, val metadata: OpenTypeMetadata, val id: String) {
        val identities = metadata.families.map { it.lowercase(Locale.ROOT) to metadata.face }.toSet()
    }
    internal fun source(id: String): ResourceSource? = entries[id]?.let { bytes -> ResourceSource { bytes.inputStream() } }
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
        return catalog.fonts.filter { it.origin == FontOrigin.MOUNTED && it.family.lowercase(Locale.ROOT) in wanted }.mapNotNull { it.resource }.distinct()
            .associateWith { id -> ResourceSource { entries.getValue(id).inputStream() } }
    }

    fun declarations(ids: Set<String>): List<Map<String, String>> = catalog.fonts.filter { it.resource in ids }
        .distinctBy { it.resource }.map { mapOf("family" to it.family, "face" to it.face, "resource" to it.resource!!) }

    private class ByteBudget {
        private var bytes = 0L
        fun add(count: Int) {
            bytes += count
            check(bytes <= 128L * 1024 * 1024) { "Combined font scan byte limit exceeded" }
        }
    }
    private class Loader(private val combined: ByteBudget?) {
        val failures = mutableListOf<FileFailure>()
        private var total = 0L
        private fun read(path: Path): ByteArray = Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= 16 * 1024 * 1024) {
                val count = input.read(buffer, 0, minOf(buffer.size, 16 * 1024 * 1024 + 1 - output.size()))
                if (count < 0) break
                total += count
                combined?.add(count)
                check(total <= 64L * 1024 * 1024) { "Font aggregate byte limit exceeded" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        fun candidate(path: Path): Candidate? {
            val bytes = try {
                require(Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).isRegularFile) {
                    "Font files must be regular files without symlinks"
                }
                read(path)
            } catch (error: java.io.IOException) {
                failures.add(FileFailure(path.fileName.toString(), "Cannot read font; check file permissions and mount stability", error)); return null
            } catch (error: SecurityException) {
                failures.add(FileFailure(path.fileName.toString(), "Access to font denied; check file permissions", error)); return null
            } catch (error: IllegalArgumentException) {
                failures.add(FileFailure(path.fileName.toString(), "Font must be a regular file without symlinks", error)); return null
            }
            if (bytes.size > 16 * 1024 * 1024) {
                failures.add(FileFailure(path.fileName.toString(), "Font exceeds the 16 MiB per-file limit",
                    IllegalArgumentException("Font file byte limit exceeded"))); return null
            }
            val metadata = try { OpenTypeMetadata.read(bytes) } catch (error: GenerationException) {
                val reason = error.diagnostics.firstOrNull()?.rejectionReason?.message ?: "Invalid or unsupported font metadata"
                failures.add(FileFailure(path.fileName.toString(), reason, error)); return null
            }
            val id = "nf-mounted-" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            return Candidate(path.fileName.toString(), bytes, metadata, id)
        }
    }

    companion object {
        @JvmStatic fun configured(): DeploymentFonts = load(
            System.getProperty("nuaforma.fonts.bundledDirectories")?.split(java.io.File.pathSeparator)?.map { Path.of(it) } ?: emptyList(),
            System.getProperty("nuaforma.fonts.directory")?.let { Path.of(it) })

        /** Explicit IMAGE roots and optional MOUNT root; byte snapshots never consult ambient font directories. */
        @JvmStatic fun load(bundledDirectories: List<Path>, mountedDirectory: Path?): DeploymentFonts {
            require(bundledDirectories.size <= 8) { "Bundled font directory limit exceeded" }
            val budget = ByteBudget()
            val bundled = bundledDirectories.map { snapshot(it, FontOrigin.BUNDLED, true, budget) }
            val mounted = mountedDirectory?.let { snapshot(it, FontOrigin.MOUNTED, false, budget) }
            val snapshots = bundled + listOfNotNull(mounted)
            val entries = snapshots.flatMap { it.entries.entries }.associate { it.key to it.value }
            require(entries.size <= 256 && entries.values.sumOf { it.size.toLong() } <= 128L * 1024 * 1024) { "Combined font catalog budget exceeded" }
            // Keep source identities; selection resolves MOUNT before IMAGE and rejects same-tier ambiguity.
            val groups = snapshots.flatMap { it.catalog.fonts }.groupBy { Triple(it.family.lowercase(Locale.ROOT), it.face, it.origin) }
            val conflicts = snapshots.flatMap { it.conflicts }.toSet() + groups.filterValues { fonts -> fonts.map { it.resource }.distinct().size > 1 }
                .keys
            val fonts = groups.values.filter { fonts -> fonts.map { it.resource }.distinct().size == 1 }.map { it.first() }
            return DeploymentFonts(entries, FontCatalog(fonts), snapshots.flatMap { it.failures }, conflicts)
        }

        @JvmStatic fun load(directory: Path): DeploymentFonts = snapshot(directory, FontOrigin.MOUNTED, false)

        private fun snapshot(directory: Path, origin: FontOrigin, recursive: Boolean, budget: ByteBudget? = null): DeploymentFonts {
            require(directory.isAbsolute) { "Configure an absolute font mount directory" }
            val attributes = try { Files.readAttributes(directory, BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
                catch (_: NoSuchFileException) { return DeploymentFonts(emptyMap(), FontCatalog(emptyList())) }
            require(attributes.isDirectory) { "Font mount must be a directory without symlinks" }
            val entryLimit = if (recursive) 1024 else 256
            val paths = (if (recursive) Files.walk(directory, 8) else Files.list(directory)).use { stream ->
                stream.filter { it != directory }.limit(entryLimit + 1L).toList()
            }
            require(paths.size <= entryLimit) { "Font directory entry limit exceeded" }
            val files = paths.filter { it.fileName.toString().substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("ttf", "otf", "ttc", "woff", "woff2") }
            require(files.size <= if (recursive) 256 else 64) { "Font file limit exceeded" }
            val loader = Loader(budget)
            val candidates = files.sortedBy { it.fileName.toString() }.mapNotNull { loader.candidate(it) }
            val failures = loader.failures
            val accepted = candidates.filter { candidate ->
                val conflict = candidates.firstOrNull { other -> other.id != candidate.id && other.identities.any { it in candidate.identities } }
                if (conflict != null) failures.add(FileFailure(candidate.file, "Conflicting case-insensitive family/face; all involved files excluded",
                    IllegalArgumentException("Conflicting mounted font identity"), conflict.file))
                conflict == null
            }.distinctBy { it.id }
            val fonts = accepted.flatMap { candidate -> candidate.metadata.families.sorted().map { family ->
                AvailableFont(family, candidate.metadata.face, origin, candidate.id)
            } }.distinctBy { it.family.lowercase(Locale.ROOT) to it.face }
            val conflicts = candidates.filter { candidate -> candidates.any { other -> other.id != candidate.id && other.identities.any { it in candidate.identities } } }
                .flatMap { it.identities }.map { Triple(it.first, it.second, origin) }.toSet()
            return DeploymentFonts(accepted.associate { it.id to it.bytes }, FontCatalog(fonts), failures.toList(), conflicts)
        }
    }
}
