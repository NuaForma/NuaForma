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
                        val iterator = jar.versionedStream().iterator()
                        var count = 0
                        while (iterator.hasNext()) {
                            require(++count <= 100000) { "EXTENSION_ARCHIVE_LIMIT" }
                            val entry = iterator.next()
                            val name = entry.name
                            require(!name.startsWith('/') && !name.contains('\\') && name.split('/').none { it == ".." }) { "INVALID_EXTENSION_ARCHIVE" }
                            if (!entry.isDirectory && name.endsWith(".class") && !name.startsWith("META-INF/") && name != "module-info.class") ownClasses.add(name)
                        }
                        for (name in ownClasses) {
                            require(classes.add(name) && parent.getResource(name) == null) { "EXTENSION_CLASS_CONFLICT" }
                        }
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
}
