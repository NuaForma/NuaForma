package dev.nuaforma.server

import dev.nuaforma.contracts.*
import dev.nuaforma.core.ProviderRegistry
import dev.nuaforma.server.adapters.ktor.KtorServer
import dev.nuaforma.server.internal.ExtensionClasspath
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.tools.ToolProvider

class MountedValuePipesTest {
    @TempDir lateinit var temporary: Path
    private val parent = javaClass.classLoader

    @Test
    fun `mounted Java 17 pipes survive source changes and concurrent request classloaders until restart`() {
        val directory = Files.createDirectory(temporary.resolve("extensions"))
        val mounted = Files.copy(fixture(temporary.resolve("first"), "V1-"), directory.resolve("pipes.jar"))
        val loader = ExtensionClasspath.open(directory, parent)
        val snapshot = Path.of(loader.urLs.single().toURI()).parent
        try {
            val registry = discoverAtStartup(loader)
            assertSame(parent, Thread.currentThread().contextClassLoader)
            assertTrue(registry.diagnostics.isEmpty(), registry.diagnostics.joinToString { it.message })
            assertEquals(2, registry.providers.size)
            registry.providers.forEach { registration ->
                val metadata = (registration.provider as TemplatePipeProvider).templatePipes()
                assertEquals(listOf("mountedIdentity", "mountedLabel"), metadata.map { it.name })
                assertTrue(metadata.all { it.chainable })
                assertThrows(UnsupportedOperationException::class.java) { (metadata as MutableList).clear() }
            }
            Files.copy(fixture(temporary.resolve("second"), "V2-"), mounted, StandardCopyOption.REPLACE_EXISTING)
            val source = template("Before {name | mountedIdentity() | mountedLabel(&quot;!&quot;)} after")
            val executor = Executors.newFixedThreadPool(4)
            try {
                val results = executor.invokeAll((0..7).map { index -> Callable {
                    assertNotSame(loader, Thread.currentThread().contextClassLoader)
                    val provider = if (index % 2 == 0) "nuaforma-docx" else "nuaforma-docx-pdf"
                    val parts = entries(generate(registry, source, "Tenant-$index", provider))
                    assertTrue(parts.getValue("word/document.xml").contains("V1-Tenant-$index!"))
                    val headers = parts.filterKeys { it.startsWith("word/nf-header-") }.values.joinToString()
                    assertTrue(headers.contains("MARK V1-Tenant-$index!"), headers)
                    (0..7).filter { it != index }.forEach { other ->
                        assertFalse(parts.values.any { it.contains("Tenant-$other!") })
                    }
                    assertFalse(parts.values.any { it.contains("V2-") || it.contains("mountedLabel") })
                    parts.getValue("word/document.xml")
                } }).map { it.get() }
                assertEquals(8, results.distinct().size)
            } finally { executor.shutdownNow() }
            ExtensionClasspath.open(directory, parent).use { restarted ->
                val parts = entries(generate(discoverAtStartup(restarted), source, "Restart", "nuaforma-docx"))
                assertTrue(parts.getValue("word/document.xml").contains("V2-Restart!"))
            }
            assertTrue(Files.exists(snapshot))
        } finally { loader.close() }
        assertFalse(Files.exists(snapshot))
    }

    @Test
    fun `mounted value pipes have identical multipart HTTP and embedded document and watermark semantics`() = testApplication {
        val directory = Files.createDirectory(temporary.resolve("extensions"))
        Files.copy(fixture(temporary.resolve("fixture"), "MOUNT-"), directory.resolve("pipes.jar"))
        val loader = ExtensionClasspath.open(directory, parent)
        val requestFiles = Files.createDirectory(temporary.resolve("requests"))
        try {
            val registry = discoverAtStartup(loader)
            application { KtorServer(registry, ServerConfiguration(temporaryDirectory = requestFiles)).installRoutes(this) }
            val source = template("Before {name | mountedIdentity() | mountedLabel(&quot;!&quot;)} after")
            val json = """{"input":{"format":"docx"},"output":{"format":"docx"},"providerId":"nuaforma-docx","data":{"name":"{literal}"},"options":{"watermark":{"text":"MARK {name | mountedLabel(\"!\")}"}}}"""
            val response = client.post("/api/v1/documents") {
                contentType(ContentType.MultiPart.FormData.withParameter("boundary", "MOUNTBOUNDARY"))
                setBody(multipart(json, source))
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val actual = normalized(entries(response.bodyAsBytes()))
            val expected = normalized(entries(generate(registry, source, "{literal}", "nuaforma-docx")))
            assertEquals(expected, actual)
            assertTrue(actual.getValue("word/document.xml").contains("MOUNT-{literal}!"))
            assertTrue(actual.values.any { it.contains("MARK MOUNT-{literal}!") })
            val rejected = client.post("/api/v1/documents") {
                contentType(ContentType.MultiPart.FormData.withParameter("boundary", "MOUNTBOUNDARY"))
                setBody(multipart(json, template("{name | unknownMountedPipe()}")))
            }
            assertEquals(HttpStatusCode.UnprocessableEntity, rejected.status)
            assertFalse(rejected.bodyAsText().contains("{literal}"))
            Files.list(requestFiles).use { assertEquals(0, it.count()) }
        } finally { loader.close() }
    }

    @Test
    fun `incompatible mounted pipe metadata fails before any static initialization`() {
        val directory = Files.createDirectory(temporary.resolve("extensions"))
        val marker = "nuaforma.test.mounted-pipe.${UUID.randomUUID()}"
        Files.copy(fixture(temporary.resolve("incompatible"), "NEVER-", ContractVersion.CURRENT.minor + 1, marker), directory.resolve("pipes.jar"))
        try {
            ExtensionClasspath.open(directory, parent).use { loader ->
                val registry = discoverAtStartup(loader)
                assertTrue(registry.providers.isEmpty())
                assertFalse(registry.diagnostics.isEmpty())
                assertNull(System.getProperty(marker))
                assertSame(parent, Thread.currentThread().contextClassLoader)
            }
        } finally { System.clearProperty(marker) }
    }

    private fun discoverAtStartup(loader: ClassLoader): ProviderRegistry {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        return try {
            thread.contextClassLoader = loader
            ProviderRegistry.discover(loader)
        } finally { thread.contextClassLoader = previous }
    }

    private fun generate(registry: ProviderRegistry, source: ByteArray, name: String, provider: String): ByteArray {
        val requirements = GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.DOCX),
            GenerationOptions(TextWatermark("MARK {name | mountedLabel(\"!\")}")))
        return ByteArrayOutputStream().also { output ->
            registry.generate(GenerationRequest(source.inputStream(), requirements, mapOf("name" to name)), output, GenerationContext(), provider)
        }.toByteArray()
    }

    private fun normalized(parts: Map<String, String>): Map<String, String> {
        val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val shape = Regex("NFWatermark[0-9a-f]{32}")
        return parts.mapKeys { uuid.replace(it.key, "UUID") }.mapValues { shape.replace(uuid.replace(it.value, "UUID"), "NFWatermarkUUID") }
    }

    private fun entries(bytes: ByteArray): Map<String, String> = linkedMapOf<String, String>().also { result ->
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) { result[entry.name] = zip.readBytes().decodeToString(); entry = zip.nextEntry }
        }
    }

    private fun multipart(json: String, source: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        for ((name, type, bytes) in listOf(Triple("request", "application/json", json.toByteArray()), Triple("template", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", source))) {
            output.write("--MOUNTBOUNDARY\r\nContent-Disposition: form-data; name=\"$name\"; filename=\"$name\"\r\nContent-Type: $type\r\n\r\n".toByteArray())
            output.write(bytes); output.write("\r\n".toByteArray())
        }
        output.write("--MOUNTBOUNDARY--\r\n".toByteArray())
    }.toByteArray()

    private fun template(text: String): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            mapOf(
                "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""",
                "_rels/.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="r1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""",
                "word/document.xml" to """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>$text</w:t></w:r></w:p><w:sectPr/></w:body></w:document>""",
            ).forEach { (name, content) -> zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry() }
        }
    }.toByteArray()

    /** Compile extension code against Java 17 contracts and Kotlin only, independently of engine/server classes. */
    private fun fixture(root: Path, prefix: String, minor: Int = ContractVersion.CURRENT.minor, marker: String? = null): Path {
        Files.createDirectories(root)
        val classes = Files.createDirectory(root.resolve("classes"))
        val source = root.resolve("MountedLabel.java")
        Files.writeString(source, """
            package independent;
            import dev.nuaforma.contracts.*;
            import java.util.*;
            public class MountedLabel implements TemplatePipe {
                ${marker?.let { "static { System.setProperty(\"$it\", \"initialized\"); }" } ?: ""}
                public TemplatePipeDefinition getDefinition() {
                    return new TemplatePipeDefinition("mountedLabel", "Independent mounted label", List.of(new OptionDefinition("suffix", OptionType.STRING, "Literal suffix", true)), "Null is an empty label");
                }
                public Object apply(Object value, List<? extends Object> arguments, TemplatePipeContext context) throws GenerationException {
                    context.checkActive(); return "$prefix" + (value == null ? "" : (String) value) + (String) arguments.get(0);
                }
            }
        """.trimIndent())
        val identity = root.resolve("MountedIdentity.java")
        Files.writeString(identity, """
            package independent;
            import dev.nuaforma.contracts.*;
            import java.util.*;
            public class MountedIdentity implements TemplatePipe {
                public TemplatePipeDefinition getDefinition() { return new TemplatePipeDefinition("mountedIdentity", "Independent identity"); }
                public Object apply(Object value, List<? extends Object> arguments, TemplatePipeContext context) throws GenerationException { context.checkActive(); return value; }
            }
        """.trimIndent())
        val classpath = listOf(TemplatePipe::class.java, Unit::class.java).joinToString(File.pathSeparator) {
            Path.of(it.protectionDomain.codeSource.location.toURI()).toString()
        }
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "17", "-cp", classpath, "-d", classes.toString(), source.toString(), identity.toString()) == 0)
        return root.resolve("mounted-pipes.jar").also { jar ->
            JarOutputStream(Files.newOutputStream(jar)).use { output ->
                fun entry(name: String, bytes: ByteArray) { output.putNextEntry(JarEntry(name)); output.write(bytes); output.closeEntry() }
                // Reverse service order deliberately: metadata order must not depend on classpath order.
                entry("META-INF/services/dev.nuaforma.contracts.TemplatePipe", "independent.MountedLabel\nindependent.MountedIdentity\n".toByteArray())
                for ((type, name) in listOf("MountedLabel" to "mountedLabel", "MountedIdentity" to "mountedIdentity")) {
                    entry("independent/$type.class", Files.readAllBytes(classes.resolve("independent/$type.class")))
                    entry(TemplatePipeMetadata.resourcePath("independent.$type"), "pipe.name=$name\npipe.class=independent.$type\npipe.version=0.1.0\ncontract.major=${ContractVersion.CURRENT.major}\ncontract.minor=$minor\n".toByteArray())
                }
            }
        }
    }
}
