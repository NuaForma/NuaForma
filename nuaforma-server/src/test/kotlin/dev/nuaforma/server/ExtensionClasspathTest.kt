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
        Files.copy(MountedProviderFixture.build(root.resolve("fixture"), minor = 2), directory.resolve("provider.jar"))
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
