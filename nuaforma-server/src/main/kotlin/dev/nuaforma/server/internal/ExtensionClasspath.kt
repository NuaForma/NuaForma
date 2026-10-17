package dev.nuaforma.server.internal

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.jar.JarFile

/** Operator-installed executable code; metadata scanning never initializes extension classes. */
internal object ExtensionClasspath {
    fun open(directory: Path?, parent: ClassLoader): URLClassLoader {
        if (directory == null) return URLClassLoader(emptyArray(), parent)
        require(directory.isAbsolute) { "INVALID_EXTENSION_DIRECTORY" }
        if (!Files.exists(directory, NOFOLLOW_LINKS)) return URLClassLoader(emptyArray(), parent)
        require(Files.isDirectory(directory, NOFOLLOW_LINKS)) { "INVALID_EXTENSION_DIRECTORY" }
        val entries = Files.list(directory).use { it.limit(257).toList() }
        require(entries.size <= 256) { "EXTENSION_ENTRY_LIMIT" }
        val paths = entries.filter { it.fileName.toString().endsWith(".jar", ignoreCase = true) }.sortedBy { it.fileName.toString() }
        if (paths.isEmpty()) return URLClassLoader(emptyArray(), parent)
        val temporary = Path.of(System.getProperty("java.io.tmpdir"))
        val snapshot = if (Files.getFileStore(temporary).supportsFileAttributeView("posix"))
            Files.createTempDirectory(temporary, "nf-extensions-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        else Files.createTempDirectory(temporary, "nf-extensions-")
        val copies = mutableListOf<Path>()
        try {
            val classes = mutableSetOf<String>()
            var total = 0L
            for (path in paths) {
                require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "INVALID_EXTENSION_FILE" }
                val copy = snapshot.resolve("extension-${copies.size}.jar")
                copies.add(copy)
                Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
                    Files.newOutputStream(copy).use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val size = input.read(buffer)
                            if (size < 0) break
                            total += size
                            require(total <= 256L * 1024 * 1024) { "EXTENSION_BYTE_LIMIT" }
                            output.write(buffer, 0, size)
                        }
                    }
                }
                try {
                    JarFile(copy.toFile(), false, JarFile.OPEN_READ, Runtime.version()).use { jar ->
                        require(jar.manifest?.mainAttributes?.getValue("Class-Path").isNullOrBlank()) { "EXTENSION_MANIFEST_CLASSPATH" }
                        val ownClasses = linkedSetOf<String>()
                        require(jar.size() <= 100000) { "EXTENSION_ARCHIVE_LIMIT" }
                        val rawEntries = jar.entries()
                        var rawCount = 0
                        while (rawEntries.hasMoreElements()) {
                            require(++rawCount <= 100000) { "EXTENSION_ARCHIVE_LIMIT" }
                            val entry = rawEntries.nextElement()
                            validateArchiveName(entry.name, entry.isDirectory)
                        }
                        val iterator = jar.versionedStream().iterator()
                        var count = 0
                        while (iterator.hasNext()) {
                            require(++count <= 100000) { "EXTENSION_ARCHIVE_LIMIT" }
                            val entry = iterator.next()
                            val name = entry.name
                            validateArchiveName(name, entry.isDirectory)
                            if (!entry.isDirectory && name.endsWith(".class") && !name.startsWith("META-INF/") && name != "module-info.class") {
                                require(ownClasses.add(name) && name !in classes && parent.getResource(name) == null) { "EXTENSION_CLASS_CONFLICT" }
                                require(entry.size in 8..(16L * 1024 * 1024)) { "INVALID_EXTENSION_CLASS" }
                                jar.getInputStream(entry).use { input ->
                                    val header = input.readNBytes(8)
                                    require(header.size == 8 && header.take(4) == listOf(0xca.toByte(), 0xfe.toByte(), 0xba.toByte(), 0xbe.toByte())) { "INVALID_EXTENSION_CLASS" }
                                    val minor = ((header[4].toInt() and 255) shl 8) or (header[5].toInt() and 255)
                                    val major = ((header[6].toInt() and 255) shl 8) or (header[7].toInt() and 255)
                                    require(major in 45..(Runtime.version().feature() + 44) && minor != 65535) { "UNSUPPORTED_EXTENSION_CLASS_VERSION" }
                                }
                            }
                        }
                        classes.addAll(ownClasses)
                    }
                } catch (_: java.io.IOException) { throw IllegalArgumentException("INVALID_EXTENSION_JAR") }
            }
            return object : URLClassLoader(copies.map { it.toUri().toURL() }.toTypedArray(), parent) {
                override fun close() {
                    try { super.close() }
                    finally { copies.forEach { Files.deleteIfExists(it) }; Files.deleteIfExists(snapshot) }
                }
            }
        } catch (failure: Throwable) {
            copies.forEach { Files.deleteIfExists(it) }
            Files.deleteIfExists(snapshot)
            throw failure
        }
    }

    private fun validateArchiveName(name: String, directory: Boolean) {
        require(name.isNotEmpty() && !name.startsWith('/') && name.none { it == '\\' || it == ':' || it == '\u0000' }) {
            "INVALID_EXTENSION_ARCHIVE"
        }
        val relative = if (directory) name.dropLast(1) else name
        require(relative.isNotEmpty() && relative.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
            "INVALID_EXTENSION_ARCHIVE"
        }
    }
}
