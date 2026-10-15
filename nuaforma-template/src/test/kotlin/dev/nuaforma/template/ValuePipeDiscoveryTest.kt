package dev.nuaforma.template

import dev.nuaforma.contracts.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.net.URLConnection
import java.net.URLStreamHandler
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.Locale
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider

class ValuePipeDiscoveryTest {
    @TempDir lateinit var directory: Path

    @AfterEach
    fun removeFixtureInitializationMarkers() {
        val prefix = "nuaforma.value-pipe.fixture.${directory.fileName}."
        System.getProperties().stringPropertyNames().filter { it.startsWith(prefix) }.forEach(System::clearProperty)
    }

    @Test
    fun `independent Java 17 SPI JAR chains literal values through the public API`() {
        val fixtures = PipeJars(directory)
        fixtures.loader(fixtures.jar("Happy")).use { loader ->
            val pipes = TemplatePipes.discover(loader)
            assertEquals(listOf("happy"), pipes.definitions().map { it.name })
            assertTrue(pipes.definitions().single().chainable)
            assertEquals("yes", System.getProperty(fixtures.key("Happy")))
            val template = Templates.compile("{value | happy(\"A\") | happy(\"B\")}", pipes)
            assertEquals("BA{literal}", template.render(mapOf("value" to "{literal}")))
            assertThrows(UnsupportedOperationException::class.java) { (pipes.definitions() as MutableList).clear() }
        }
    }

    @Test
    fun `registration order does not affect immutable definition order or independent registry lifetime`() {
        val fixtures = PipeJars(directory)
        val alpha = fixtures.jar("Alpha")
        val zulu = fixtures.jar("Zulu")
        fixtures.loader(zulu, alpha).use { loader ->
            assertEquals(listOf("alpha", "zulu"), TemplatePipes.discover(loader).definitions().map { it.name })
        }
        fixtures.loader().use { loader -> assertTrue(TemplatePipes.discover(loader).definitions().isEmpty()) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "duplicate", "unknown", "wrong-class", "oversize", "bad-version", "bad-minor", "bad-service", "missing-class", "reserved-name"])
    fun `invalid static declarations fail before any implementation load or initialization`(kind: String) {
        val fixtures = PipeJars(directory)
        val name = "Invalid" + kind.replace("-", "")
        val good = fixtures.metadata(name)
        val descriptor = when (kind) {
            "missing" -> null
            "duplicate" -> good + "\ncontract.minor=1"
            "unknown" -> good + "\nunknown=true"
            "wrong-class" -> good.replace("independent.$name", "independent.Other")
            "oversize" -> " ".repeat(8193)
            "bad-version" -> good.replace("pipe.version=2.3.4", "pipe.version=bad")
            "bad-minor" -> good.replace("contract.minor=2", "contract.minor=02")
            "reserved-name" -> good.replace("pipe.name=${name.lowercase(Locale.ROOT)}", "pipe.name=number")
            else -> good
        }
        val jar = fixtures.jar(name, descriptor = descriptor, includeClass = kind != "missing-class",
            service = if (kind == "bad-service") "../Invalid\n" else "independent.$name\n")
        fixtures.loader(jar).use { loader ->
            rejected { TemplatePipes.discover(loader) }
            assertNull(System.getProperty(fixtures.key(name)))
            assertTrue(loader.implementationLoads.isEmpty())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["1.0", "0.3"])
    fun `incompatible major and minimum minor fail before any implementation initialization`(version: String) {
        val fixtures = PipeJars(directory)
        fixtures.loader(fixtures.jar("Future", descriptor = fixtures.metadata("Future", version))).use { loader ->
            rejected { TemplatePipes.discover(loader) }
            assertNull(System.getProperty(fixtures.key("Future")))
            assertTrue(loader.implementationLoads.isEmpty())
        }
    }

    @Test
    fun `duplicate names are rejected in either classpath order before either class is loaded`() {
        val fixtures = PipeJars(directory)
        val first = fixtures.jar("DuplicateA", pipeName = "duplicate")
        val second = fixtures.jar("DuplicateB", pipeName = "duplicate")
        for (jars in listOf(arrayOf(first, second), arrayOf(second, first))) fixtures.loader(*jars).use { loader ->
            rejected { TemplatePipes.discover(loader) }
            assertTrue(loader.implementationLoads.isEmpty())
        }
        assertNull(System.getProperty(fixtures.key("DuplicateA")))
        assertNull(System.getProperty(fixtures.key("DuplicateB")))
    }

    @Test
    fun `all metadata is checked before initializing even an earlier valid registration`() {
        val fixtures = PipeJars(directory)
        fixtures.loader(fixtures.jar("Accepted"), fixtures.jar("Rejected", descriptor = null)).use { loader ->
            rejected { TemplatePipes.discover(loader) }
            assertNull(System.getProperty(fixtures.key("Accepted")))
            assertNull(System.getProperty(fixtures.key("Rejected")))
            assertTrue(loader.implementationLoads.isEmpty())
        }
    }

    @Test
    fun `descriptor and implementation cannot be split across artifacts or shadowed`() {
        val fixtures = PipeJars(directory)
        val implementation = fixtures.jar("Split", descriptor = null)
        val descriptor = fixtures.jar("Split", includeClass = false, service = null)
        fixtures.loader(implementation, descriptor).use { loader ->
            rejected { TemplatePipes.discover(loader) }
            assertTrue(loader.implementationLoads.isEmpty())
        }
        fixtures.loader(fixtures.jar("Shadow"), fixtures.jar("Shadow")).use { loader ->
            rejected { TemplatePipes.discover(loader) }
            assertTrue(loader.implementationLoads.isEmpty())
        }
        assertNull(System.getProperty(fixtures.key("Split")))
        assertNull(System.getProperty(fixtures.key("Shadow")))
    }

    @Test
    fun `wrong interface mismatched name and failing initializer produce sanitized failures`() {
        val fixtures = PipeJars(directory)
        val invalid = listOf(
            "WrongType" to fixtures.jar("WrongType", transform = { it.replace("implements TemplatePipe", "") }),
            "WrongName" to fixtures.jar("WrongName", descriptor = fixtures.metadata("WrongName", pipeName = "different")),
            "Initializer" to fixtures.jar("Initializer", transform = {
                it.replace("static {", "static { if (Boolean.parseBoolean(\"true\")) throw new IllegalStateException(\"PRIVATE_FIXTURE_MARKER\");")
            }),
        )
        for ((name, jar) in invalid) fixtures.loader(jar).use { loader ->
            val error = rejected { TemplatePipes.discover(loader) }
            assertFalse(error.message.orEmpty().contains("PRIVATE_FIXTURE_MARKER"))
            assertNull(error.cause)
            if (name == "WrongType") assertNull(System.getProperty(fixtures.key(name)))
        }
    }

    @Test
    fun `local class directories accept normal comments and repeated identical service lines`() {
        val fixtures = PipeJars(directory)
        val jar = fixtures.jar("Exploded", service = "# Header\nindependent.Exploded # inline\nindependent.Exploded\n")
        fixtures.loader(fixtures.unpack(jar)).use { loader ->
            assertEquals(listOf("exploded"), TemplatePipes.discover(loader).definitions().map { it.name })
        }
    }

    @Test
    fun `remote service resources are rejected without opening a connection`() {
        var connections = 0
        val remote = URL(null, "https://invalid.example/META-INF/services/dev.nuaforma.contracts.TemplatePipe", object : URLStreamHandler() {
            override fun openConnection(url: URL): URLConnection { connections++; error("Must not connect") }
        })
        val loader = object : ClassLoader(TemplatePipe::class.java.classLoader) {
            override fun getResources(name: String) = Collections.enumeration(listOf(remote))
        }
        rejected { TemplatePipes.discover(loader) }
        assertEquals(0, connections)
    }

    private fun rejected(action: () -> Any?): GenerationException =
        assertThrows(GenerationException::class.java) { action() }.also { error ->
            assertEquals(DiagnosticCodes.INVALID_REQUEST, error.diagnostics.single().code)
            assertFalse(error.diagnostics.single().message.contains("PRIVATE_FIXTURE_MARKER"))
        }

    /** Independent implementation compilation sees contracts and Kotlin only, not the evaluator. */
    private class PipeJars(private val root: Path) {
        private var sequence = 0
        private val prefix = "nuaforma.value-pipe.fixture.${root.fileName}."
        fun key(name: String) = prefix + name
        fun metadata(name: String, version: String = "0.2", pipeName: String = name.lowercase(Locale.ROOT)) = """
            pipe.name=$pipeName
            pipe.class=independent.$name
            pipe.version=2.3.4
            contract.major=${version.substringBefore('.')}
            contract.minor=${version.substringAfter('.')}
        """.trimIndent()

        fun jar(name: String, pipeName: String = name.lowercase(Locale.ROOT), descriptor: String? = metadata(name, pipeName = pipeName),
                includeClass: Boolean = true, service: String? = "independent.$name\n", transform: (String) -> String = { it }): Path {
            val folder = Files.createDirectory(root.resolve("pipe-${sequence++}"))
            val classes = Files.createDirectory(folder.resolve("classes"))
            if (includeClass) {
                val source = """
                    package independent;
                    import dev.nuaforma.contracts.*;
                    import java.util.List;
                    public final class $name implements TemplatePipe {
                        static { System.setProperty("${key(name)}", "yes"); }
                        public TemplatePipeDefinition getDefinition() {
                            return new TemplatePipeDefinition("$pipeName", "Independent Java pipe", List.of(
                                new OptionDefinition("prefix", OptionType.STRING, "Literal prefix", true)));
                        }
                        public Object apply(Object value, List<? extends Object> arguments, TemplatePipeContext context) throws GenerationException {
                            context.checkActive();
                            return arguments.get(0).toString() + (String) value;
                        }
                    }
                """.trimIndent()
                val java = folder.resolve("$name.java")
                Files.writeString(java, transform(source))
                val classpath = listOf(TemplatePipe::class.java, Unit::class.java)
                    .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.joinToString(File.pathSeparator)
                val messages = ByteArrayOutputStream()
                val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler())
                assertEquals(0, compiler.run(null, messages, messages, "--release", "17", "-classpath", classpath,
                    "-d", classes.toString(), java.toString()), messages.toString(Charsets.UTF_8))
            }
            val jar = folder.resolve("pipe.jar")
            JarOutputStream(Files.newOutputStream(jar)).use { output ->
                fun entry(path: String, bytes: ByteArray) {
                    output.putNextEntry(JarEntry(path)); output.write(bytes); output.closeEntry()
                }
                Files.walk(classes).use { files -> files.filter { Files.isRegularFile(it) }.sorted().forEach {
                    entry(classes.relativize(it).toString().replace('\\', '/'), Files.readAllBytes(it))
                } }
                if (descriptor != null) entry(TemplatePipeMetadata.resourcePath("independent.$name"), descriptor.toByteArray(Charsets.ISO_8859_1))
                if (service != null) entry("META-INF/services/dev.nuaforma.contracts.TemplatePipe", service.toByteArray(Charsets.UTF_8))
            }
            return jar
        }

        fun loader(vararg jars: Path) = TrackingLoader(jars.map { it.toUri().toURL() }.toTypedArray())
        fun unpack(jar: Path): Path {
            val folder = Files.createDirectory(root.resolve("unpacked-${sequence++}"))
            JarFile(jar.toFile()).use { archive -> for (entry in archive.entries()) {
                val destination = folder.resolve(entry.name).normalize()
                require(destination.startsWith(folder))
                Files.createDirectories(destination.parent)
                archive.getInputStream(entry).use { Files.copy(it, destination) }
            } }
            return folder
        }
    }

    private class TrackingLoader(urls: Array<URL>) : URLClassLoader(urls, TemplatePipe::class.java.classLoader) {
        val implementationLoads = mutableListOf<String>()
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name.startsWith("independent.")) implementationLoads += name
            return super.loadClass(name, resolve)
        }
    }
}
