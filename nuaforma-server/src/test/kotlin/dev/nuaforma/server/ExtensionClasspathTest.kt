package dev.nuaforma.server

import dev.nuaforma.contracts.*
import dev.nuaforma.core.ProviderRegistry
import dev.nuaforma.server.internal.ExtensionClasspath
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.Manifest
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.JarFile

class ExtensionClasspathTest {
    @TempDir lateinit var root: Path
    private val parent = javaClass.classLoader
    private fun mount() = Files.createDirectory(root.resolve("extensions"))

    @Test fun `unsafe raw archive names fail before any parent lookup and clean their snapshot`() {
        val directory = mount()
        val lookups = mutableListOf<String>()
        val loaded = mutableListOf<String>()
        val observingParent = object : ClassLoader(parent) {
            override fun getResource(name: String): java.net.URL? {
                lookups.add(name)
                return super.getResource(name)
            }
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                loaded.add(name)
                return super.loadClass(name, resolve)
            }
        }
        val before = snapshots()
        for (name in listOf("../escape.class", "nested/../escape.class", "/absolute.class", "\\absolute.class",
                "nested\\escape.class", "C:/absolute.class", "C:relative.class", "file:escape.class", "./escape.class",
                "nested/./escape.class", "nested//escape.class", "../resource.txt", "nested/../", "/", "./", "//")) {
            JarOutputStream(Files.newOutputStream(directory.resolve("unsafe.jar"))).use {
                it.putNextEntry(JarEntry("safe/First.class")); it.write(classHeader()); it.closeEntry()
                it.putNextEntry(JarEntry(name)); it.write(byteArrayOf(1)); it.closeEntry()
            }
            assertEquals("INVALID_EXTENSION_ARCHIVE", assertThrows(IllegalArgumentException::class.java) {
                ExtensionClasspath.open(directory, observingParent)
            }.message, name)
            assertTrue(lookups.isEmpty(), "Parent lookup before complete archive-name validation")
            assertTrue(loaded.isEmpty(), "Class loading during archive inspection")
            assertEquals(before, snapshots(), "Rejected archive left a private snapshot")
        }
    }

    @Test fun `hidden multi release archive paths are rejected before inspecting effective classes`() {
        val directory = mount()
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.MULTI_RELEASE] = "true"
        }
        val lookups = mutableListOf<String>()
        val observingParent = object : ClassLoader(parent) {
            override fun getResource(name: String): java.net.URL? { lookups.add(name); return null }
        }
        for (name in listOf("META-INF/versions/99/../escape.class", "META-INF/versions/99/nested//escape.class",
                "META-INF/versions/99/nested/./resource.txt")) {
            JarOutputStream(Files.newOutputStream(directory.resolve("multi.jar")), manifest).use {
                it.putNextEntry(JarEntry("safe/First.class")); it.write(classHeader()); it.closeEntry()
                it.putNextEntry(JarEntry(name)); it.write(classHeader()); it.closeEntry()
            }
            assertEquals("INVALID_EXTENSION_ARCHIVE", assertThrows(IllegalArgumentException::class.java) {
                ExtensionClasspath.open(directory, observingParent)
            }.message)
            assertTrue(lookups.isEmpty())
        }
    }

    @Test fun `canonical archive resources and unicode inner classes remain literal and are not loaded`() {
        val directory = mount()
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.MULTI_RELEASE] = "true"
        }
        JarOutputStream(Files.newOutputStream(directory.resolve("valid.jar")), manifest).use {
            for (name in listOf("example/", "example/Caf\u00e9\$Inner.class", "META-INF/services/example.Provider",
                    "example/my resource.txt", "example/100%.txt", "META-INF/versions/25/example/Caf\u00e9\$Inner.class")) {
                it.putNextEntry(JarEntry(name)); if (!name.endsWith('/')) it.write(classHeader()); it.closeEntry()
            }
        }
        val lookups = mutableListOf<String>()
        val observingParent = object : ClassLoader(parent) {
            override fun getResource(name: String): java.net.URL? { lookups.add(name); return null }
            override fun loadClass(name: String, resolve: Boolean): Class<*> = fail("Archive inspection must not load classes")
        }
        ExtensionClasspath.open(directory, observingParent).use {
            assertEquals(listOf("example/Caf\u00e9\$Inner.class"), lookups)
        }
    }

    private fun classHeader() = byteArrayOf(0xca.toByte(), 0xfe.toByte(), 0xba.toByte(), 0xbe.toByte(), 0, 0, 0, 61)

    @Test fun `empty and NUL archive names are rejected by parsing or name validation`() {
        val directory = mount()
        val observingParent = object : ClassLoader(parent) {
            override fun getResource(name: String): java.net.URL? = fail("Malformed name reached parent lookup")
        }
        val before = snapshots()
        for (name in listOf("", "bad\u0000name.class")) {
            JarOutputStream(Files.newOutputStream(directory.resolve("bad.jar"))).use {
                it.putNextEntry(JarEntry(name)); it.write(classHeader()); it.closeEntry()
            }
            val failure = assertThrows(IllegalArgumentException::class.java) { ExtensionClasspath.open(directory, observingParent) }
            assertTrue(failure.message in setOf("INVALID_EXTENSION_ARCHIVE", "INVALID_EXTENSION_JAR"))
            assertEquals(before, snapshots())
        }
    }

    @Test fun `duplicate raw classes cannot disappear into the per jar class set`() {
        val directory = mount()
        val bytes = ByteArrayOutputStream().also { stream ->
            JarOutputStream(stream).use { jar ->
                for (name in listOf("safe/One.class", "safe/Two.class")) {
                    jar.putNextEntry(JarEntry(name)); jar.write(classHeader()); jar.closeEntry()
                }
            }
        }.toByteArray()
        // JarOutputStream rejects duplicates, so rewrite the equal-length local and central names.
        val from = "safe/Two.class".toByteArray()
        val to = "safe/One.class".toByteArray()
        for (offset in 0..bytes.size - from.size) {
            if (from.indices.all { bytes[offset + it] == from[it] }) to.copyInto(bytes, offset)
        }
        Files.write(directory.resolve("duplicate.jar"), bytes)
        val before = snapshots()
        assertEquals("EXTENSION_CLASS_CONFLICT", assertThrows(IllegalArgumentException::class.java) {
            ExtensionClasspath.open(directory, parent)
        }.message)
        assertEquals(before, snapshots())
    }

    @Test fun `percent encoded names stay literal in the standard parent classloader`() {
        val directory = mount()
        val parentRoot = Files.createDirectory(root.resolve("parent-classpath"))
        val sentinel = root.resolve("issue96-sentinel.class")
        Files.write(sentinel, classHeader())
        val names = listOf("%2e%2e/issue96-sentinel.class", "..%2fissue96-sentinel.class", "%2e%2e%5cissue96-sentinel.class")
        JarOutputStream(Files.newOutputStream(directory.resolve("literal.jar"))).use { jar ->
            for (name in names) { jar.putNextEntry(JarEntry(name)); jar.write(classHeader()); jar.closeEntry() }
        }
        java.net.URLClassLoader(arrayOf(parentRoot.toUri().toURL()), parent).use { loader ->
            for (name in names) assertNull(loader.getResource(name), "Encoded spelling escaped the parent classpath")
            ExtensionClasspath.open(directory, loader).close()
        }
        assertArrayEquals(classHeader(), Files.readAllBytes(sentinel))
        assertTrue(Files.list(parentRoot).use { it.findAny().isEmpty })
    }

    private fun snapshots(): Set<Path> = Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { paths ->
        paths.filter { it.fileName.toString().startsWith("nf-extensions-") }.toList().toSet()
    }

    @Test fun `independent provider generates through unchanged legacy contracts`() {
        val directory = mount()
        Files.copy(MountedProviderFixture.build(root.resolve("fixture")), directory.resolve("provider.jar"))
        ExtensionClasspath.open(directory, parent).use { loader ->
            val registry = ProviderRegistry.discover(loader)
            val output = ByteArrayOutputStream()
            registry.generate(GenerationRequest(byteArrayOf().inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                FormatRequest(DocumentFormat("mount-example"))), mapOf("reference" to "INDEPENDENT")), output, GenerationContext(), "mounted-example")
            assertEquals("INDEPENDENT", output.toString())
            assertFalse(registry.providers.single { it.metadata.id == "mounted-example" }.provider is FontCatalogProvider)
        }
    }

    @Test fun `incompatible metadata is rejected before initialization`() {
        val directory = mount()
        Files.copy(MountedProviderFixture.build(root.resolve("fixture"), minor = dev.nuaforma.contracts.ContractVersion.CURRENT.minor + 1), directory.resolve("provider.jar"))
        System.clearProperty("nuaforma.mount.initialized")
        try {
            ExtensionClasspath.open(directory, parent).use { loader ->
                val registry = ProviderRegistry.discover(loader)
                assertFalse(registry.providers.any { it.metadata.id == "mounted-example" })
                assertTrue(registry.diagnostics.any { it.code.id == "INCOMPATIBLE_CONTRACT" }, registry.diagnostics.map { it.code.id }.toString())
                assertNull(System.getProperty("nuaforma.mount.initialized"))
            }
        } finally { System.clearProperty("nuaforma.mount.initialized") }
    }

    @Test fun `missing dependencies and conflicting classes do not fall back by order`() {
        val directory = mount()
        val jar = Files.copy(MountedProviderFixture.build(root.resolve("fixture"), missingDependency = true), directory.resolve("provider.jar"))
        ExtensionClasspath.open(directory, parent).use { loader ->
            val registry = ProviderRegistry.discover(loader)
            assertFalse(registry.providers.any { it.metadata.id == "mounted-example" })
            assertFalse(registry.diagnostics.isEmpty())
        }
        Files.copy(jar, directory.resolve("duplicate.jar"))
        assertEquals("EXTENSION_CLASS_CONFLICT", assertThrows(IllegalArgumentException::class.java) { ExtensionClasspath.open(directory, parent) }.message)
    }

    @Test fun `absent empty and malformed directories are handled without installing code`() {
        ExtensionClasspath.open(root.resolve("absent"), parent).use { assertTrue(it.urLs.isEmpty()) }
        ExtensionClasspath.open(root, parent).use { assertTrue(it.urLs.isEmpty()) }
        Files.write(root.resolve("malformed.jar"), byteArrayOf(1, 2))
        assertEquals("INVALID_EXTENSION_JAR", assertThrows(IllegalArgumentException::class.java) { ExtensionClasspath.open(root, parent) }.message)
    }

    @Test fun `startup snapshot does not reread changed operator JARs and is removed on close`() {
        val directory = mount()
        val original = Files.copy(MountedProviderFixture.build(root.resolve("fixture")), directory.resolve("provider.jar"))
        val loader = ExtensionClasspath.open(directory, parent)
        val snapshot = Path.of(loader.urLs.single().toURI())
        try {
            Files.write(original, byteArrayOf(1, 2))
            assertTrue(ProviderRegistry.discover(loader).providers.any { it.metadata.id == "mounted-example" })
            assertTrue(Files.exists(snapshot))
        } finally { loader.close() }
        assertFalse(Files.exists(snapshot.parent))
    }

    @Test fun `manifest indirection and application class shadowing fail explicitly`() {
        val directory = mount()
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.CLASS_PATH] = "https://example.invalid/dependency.jar"
        }
        val path = directory.resolve("provider.jar")
        JarOutputStream(Files.newOutputStream(path), manifest).close()
        assertEquals("EXTENSION_MANIFEST_CLASSPATH", assertThrows(IllegalArgumentException::class.java) { ExtensionClasspath.open(directory, parent) }.message)
        JarOutputStream(Files.newOutputStream(path)).use { it.putNextEntry(JarEntry("dev/nuaforma/contracts/DocumentProvider.class")); it.write(byteArrayOf(0)); it.closeEntry() }
        assertEquals("EXTENSION_CLASS_CONFLICT", assertThrows(IllegalArgumentException::class.java) { ExtensionClasspath.open(directory, parent) }.message)
    }

    @Test fun `conflicting provider ids remain diagnostics instead of selecting an arbitrary extension`() {
        val directory = mount()
        Files.copy(MountedProviderFixture.build(root.resolve("first")), directory.resolve("first.jar"))
        Files.copy(MountedProviderFixture.build(root.resolve("second"), className = "OtherProvider"), directory.resolve("second.jar"))
        ExtensionClasspath.open(directory, parent).use {
            val registry = ProviderRegistry.discover(it)
            assertFalse(registry.providers.any { provider -> provider.metadata.id == "mounted-example" })
            assertTrue(registry.diagnostics.any { diagnostic -> diagnostic.code.id == "DUPLICATE_PROVIDER" })
        }
    }

    @Test fun `Java 17 multi release class conflicts are included in startup validation`() {
        val directory = mount()
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.MULTI_RELEASE] = "true"
        }
        JarOutputStream(Files.newOutputStream(directory.resolve("multi.jar")), manifest).use {
            it.putNextEntry(JarEntry("META-INF/versions/17/dev/nuaforma/contracts/DocumentProvider.class"))
            it.write(byteArrayOf(0)); it.closeEntry()
        }
        assertEquals("EXTENSION_CLASS_CONFLICT", assertThrows(IllegalArgumentException::class.java) { ExtensionClasspath.open(directory, parent) }.message)
    }

    @Test fun `Java 25 providers run on the server without changing embedded contracts`() {
        val directory = mount()
        Files.copy(MountedProviderFixture.build(root.resolve("fixture"), javaRelease = 25), directory.resolve("provider.jar"))
        ExtensionClasspath.open(directory, parent).use { loader ->
            assertTrue(ProviderRegistry.discover(loader).providers.any { it.metadata.id == "mounted-example" })
        }
    }

    @Test fun `newer and preview class versions fail before provider initialization`() {
        val directory = mount()
        val original = MountedProviderFixture.build(root.resolve("fixture"))
        System.clearProperty("nuaforma.mount.initialized")
        try {
            for ((minor, major) in listOf(0 to 70, 65535 to 69)) {
                JarFile(original.toFile()).use { source ->
                    JarOutputStream(Files.newOutputStream(directory.resolve("future.jar"))).use { output ->
                        for (entry in source.entries()) {
                            val bytes = source.getInputStream(entry).use { it.readAllBytes() }
                            if (entry.name.endsWith(".class")) {
                                bytes[4] = (minor shr 8).toByte(); bytes[5] = minor.toByte()
                                bytes[6] = (major shr 8).toByte(); bytes[7] = major.toByte()
                            }
                            output.putNextEntry(JarEntry(entry.name)); output.write(bytes); output.closeEntry()
                        }
                    }
                }
                assertEquals("UNSUPPORTED_EXTENSION_CLASS_VERSION",
                    assertThrows(IllegalArgumentException::class.java) { ExtensionClasspath.open(directory, parent) }.message)
                assertNull(System.getProperty("nuaforma.mount.initialized"))
            }
        } finally { System.clearProperty("nuaforma.mount.initialized") }
    }

    @Test fun `Java 25 multi release class conflicts cannot hide behind Java 17 entries`() {
        val directory = mount()
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.MULTI_RELEASE] = "true"
        }
        JarOutputStream(Files.newOutputStream(directory.resolve("multi.jar")), manifest).use {
            it.putNextEntry(JarEntry("META-INF/versions/25/dev/nuaforma/contracts/DocumentProvider.class"))
            it.write(byteArrayOf(0)); it.closeEntry()
        }
        assertEquals("EXTENSION_CLASS_CONFLICT", assertThrows(IllegalArgumentException::class.java) { ExtensionClasspath.open(directory, parent) }.message)
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
    fun `symbolic extension files and directories are rejected`() {
        val directory = mount()
        val linkedDirectory = Files.createSymbolicLink(root.resolve("linked-directory"), directory)
        assertEquals("INVALID_EXTENSION_DIRECTORY", assertThrows(IllegalArgumentException::class.java) { ExtensionClasspath.open(linkedDirectory, parent) }.message)
        val source = MountedProviderFixture.build(root.resolve("fixture"))
        Files.createSymbolicLink(directory.resolve("provider.jar"), source)
        assertEquals("INVALID_EXTENSION_FILE", assertThrows(IllegalArgumentException::class.java) { ExtensionClasspath.open(directory, parent) }.message)
    }
}
