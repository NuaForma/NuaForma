package dev.nuaforma.core

import dev.nuaforma.contracts.DocumentProvider
import dev.nuaforma.contracts.ProviderMetadata
import org.junit.jupiter.api.Assertions.assertEquals
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider

/** Reproducible fixture builder: compile against contracts and Kotlin only, then write a standard SPI JAR. */
internal class PackagedProviders(private val root: Path) {
    private var sequence = 0

    fun jar(
        name: String,
        version: String = "0.1",
        descriptor: String? = metadata(name, version),
        includeClass: Boolean = true,
        service: String? = "independent.$name\n",
        sourceTransform: (String) -> String = { it },
    ): Path {
        val folder = Files.createDirectory(root.resolve("fixture-${sequence++}"))
        val classes = Files.createDirectory(folder.resolve("classes"))
        if (includeClass) {
            val source = requireNotNull(javaClass.getResourceAsStream("/providers/FixtureProvider.java"))
                .use { it.readAllBytes().toString(Charsets.UTF_8) }.replace("PROVIDER_CLASS", name)
            val java = folder.resolve("$name.java")
            Files.writeString(java, sourceTransform(source))
            val classpath = listOf(DocumentProvider::class.java, Unit::class.java)
                .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.joinToString(File.pathSeparator)
            val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler())
            assertEquals(0, compiler.run(null, null, null, "--release", "17", "-classpath", classpath, "-d", classes.toString(), java.toString()))
        }
        val jar = folder.resolve("provider.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            fun entry(path: String, bytes: ByteArray) {
                output.putNextEntry(JarEntry(path))
                output.write(bytes)
                output.closeEntry()
            }
            Files.walk(classes).use { files ->
                files.filter { Files.isRegularFile(it) }.sorted().forEach {
                    entry(classes.relativize(it).toString().replace('\\', '/'), Files.readAllBytes(it))
                }
            }
            if (descriptor != null) entry(ProviderMetadata.resourcePath("independent.$name"), descriptor.toByteArray(Charsets.ISO_8859_1))
            if (service != null) entry("META-INF/services/dev.nuaforma.contracts.DocumentProvider", service.toByteArray(Charsets.UTF_8))
        }
        return jar
    }

    fun loader(vararg jars: Path) = TrackingLoader(jars.map { it.toUri().toURL() }.toTypedArray())

    fun unpack(jar: Path): Path {
        val folder = Files.createDirectory(root.resolve("unpacked-${sequence++}"))
        java.util.jar.JarFile(jar.toFile()).use { archive ->
            for (entry in archive.entries()) {
                val destination = folder.resolve(entry.name).normalize()
                require(destination.startsWith(folder))
                Files.createDirectories(destination.parent)
                archive.getInputStream(entry).use { Files.copy(it, destination) }
            }
        }
        return folder
    }

    class TrackingLoader(urls: Array<java.net.URL>) : URLClassLoader(urls, DocumentProvider::class.java.classLoader) {
        val providerLoads = mutableListOf<String>()
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name.startsWith("independent.")) providerLoads += name
            return super.loadClass(name, resolve)
        }
    }

    companion object {
        fun metadata(name: String, version: String = "0.1", id: String = name.lowercase().replace('_', '-')): String = """
            provider.id=$id
            provider.class=independent.$name
            provider.version=2.3.4
            contract.major=${version.substringBefore('.')}
            contract.minor=${version.substringAfter('.')}
        """.trimIndent()
    }
}
