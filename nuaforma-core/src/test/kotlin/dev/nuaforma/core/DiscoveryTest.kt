package dev.nuaforma.core

import dev.nuaforma.contracts.*
import dev.nuaforma.core.discovery.ClasspathProviders
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DiscoveryTest {
    @TempDir lateinit var directory: Path
    private fun requirements(output: DocumentFormat = DocumentFormat.DOCX, options: Map<String, *> = emptyMap<String, Any?>()) =
        GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(output, options))

    @Test
    fun `independent Java provider is discovered through standard service registration`() {
        val fixtures = PackagedProviders(directory)
        fixtures.loader(fixtures.jar("Happy")).use { loader ->
            val registry = ProviderRegistry.discover(loader)
            assertTrue(registry.diagnostics.isEmpty())
            assertEquals("happy", registry.providers.single().metadata.id)
            assertEquals("yes", System.getProperty("nuaforma.fixture.initialized.Happy"))
            assertEquals("2.3.4", registry.providers.single().metadata.releaseVersion)
            assertEquals("happy", registry.select(requirements(DocumentFormat.PDF)).metadata.id)
            assertThrows(UnsupportedOperationException::class.java) { (registry.providers as MutableList).clear() }
        }
    }

    @ParameterizedTest
    @CsvSource("1.9,1.2,true", "1.1,1.2,false", "2.0,1.2,false", "1.10,1.9,true", "1.2.7,1.2,true")
    fun `metadata compatibility is checked before factory initialization`(available: String, required: String, accepted: Boolean) {
        val fixtures = PackagedProviders(directory)
        val name = "Version" + available.replace('.', '_')
        val key = "nuaforma.fixture.initialized.$name"
        System.clearProperty(key)
        fixtures.loader(fixtures.jar(name, required)).use { loader ->
            val scanned = ClasspathProviders(loader).scan()
            assertTrue(scanned.diagnostics.isEmpty())
            assertNull(System.getProperty(key))
            assertTrue(loader.providerLoads.isEmpty())
            val result = ProviderDiscovery(ContractVersion.parse(available)).load(scanned.registrations)
            assertEquals(if (accepted) 1 else 0, result.providers.size)
            assertEquals(if (accepted) "yes" else null, System.getProperty(key))
            if (!accepted) assertEquals("INCOMPATIBLE_CONTRACT", result.diagnostics.single().code.id)
            if (!accepted) assertTrue(loader.providerLoads.isEmpty())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "duplicate", "unknown", "wrong-class", "oversize", "bad-version", "bad-service", "missing-class"])
    fun `bad descriptors are rejected without initialization`(kind: String) {
        val fixtures = PackagedProviders(directory)
        val name = "Invalid" + kind.replace("-", "")
        System.clearProperty("nuaforma.fixture.initialized.$name")
        val good = PackagedProviders.metadata(name)
        val text = when (kind) {
            "missing" -> null
            "duplicate" -> good + "\ncontract.minor=1"
            "unknown" -> good + "\nunknown=true"
            "wrong-class" -> good.replace("independent.$name", "independent.Other")
            "oversize" -> " ".repeat(8193)
            "bad-version" -> good.replace("contract.minor=1", "contract.minor=bad")
            else -> good
        }
        val jar = fixtures.jar(name, descriptor = text, includeClass = kind != "missing-class", service =
            if (kind == "bad-service") "../Invalid\n" else "independent.$name\n")
        fixtures.loader(jar).use { loader ->
            val result = ProviderRegistry.discover(loader)
            assertTrue(result.providers.isEmpty())
            assertTrue(result.diagnostics.any { it.code.id == "INVALID_PROVIDER_METADATA" })
            assertNull(System.getProperty("nuaforma.fixture.initialized.$name"))
            assertTrue(loader.providerLoads.isEmpty())
        }
    }

    @Test
    fun `metadata cannot come from a different artifact`() {
        val fixtures = PackagedProviders(directory)
        System.clearProperty("nuaforma.fixture.initialized.Split")
        val implementation = fixtures.jar("Split", descriptor = null)
        val descriptor = fixtures.jar("Split", includeClass = false, service = null)
        fixtures.loader(implementation, descriptor).use { loader ->
            val result = ProviderRegistry.discover(loader)
            assertTrue(result.providers.isEmpty())
            assertNull(System.getProperty("nuaforma.fixture.initialized.Split"))
        }
    }

    @Test
    fun `duplicate identities and class shadowing fail before initialization`() {
        val fixtures = PackagedProviders(directory)
        for (name in listOf("DuplicateA", "DuplicateB", "Shadow")) System.clearProperty("nuaforma.fixture.initialized.$name")
        val first = fixtures.jar("DuplicateA", descriptor = PackagedProviders.metadata("DuplicateA", id = "duplicate"))
        val second = fixtures.jar("DuplicateB", descriptor = PackagedProviders.metadata("DuplicateB", id = "duplicate"))
        fixtures.loader(first, second).use { loader ->
            val result = ProviderRegistry.discover(loader)
            assertTrue(result.providers.isEmpty())
            assertTrue(result.diagnostics.all { it.code.id == "DUPLICATE_PROVIDER" })
            assertNull(System.getProperty("nuaforma.fixture.initialized.DuplicateA"))
            assertNull(System.getProperty("nuaforma.fixture.initialized.DuplicateB"))
        }
        fixtures.loader(fixtures.jar("Shadow"), fixtures.jar("Shadow")).use { loader ->
            assertTrue(ProviderRegistry.discover(loader).providers.isEmpty())
            assertNull(System.getProperty("nuaforma.fixture.initialized.Shadow"))
        }
    }

    @Test
    fun `invalid provider does not stop independent valid discovery`() {
        val fixtures = PackagedProviders(directory)
        fixtures.loader(fixtures.jar("Rejected", "2.0"), fixtures.jar("Accepted")).use { loader ->
            val registry = ProviderRegistry.discover(loader)
            assertEquals("accepted", registry.select(requirements()).metadata.id)
            assertEquals("INCOMPATIBLE_CONTRACT", registry.diagnostics.single().code.id)
            assertNull(System.getProperty("nuaforma.fixture.initialized.Rejected"))
        }
    }

    @Test
    fun `selection checks subfilters ambiguity explicit preference and runtime availability`() {
        val fixtures = PackagedProviders(directory)
        fixtures.loader(fixtures.jar("SelectA"), fixtures.jar("SelectB")).use { loader ->
            val registry = ProviderRegistry.discover(loader)
            val ambiguous = assertThrows(GenerationException::class.java) { registry.select(requirements()) }
            assertEquals("AMBIGUOUS_PROVIDER", ambiguous.diagnostics.first().code.id)
            assertEquals("selectb", registry.select(requirements(), "selectb").metadata.id)
            assertThrows(GenerationException::class.java) { registry.select(requirements(), "missing") }
            for (options in listOf(mapOf("version" to "3.0"), mapOf("version" to null), mapOf("version" to "2.0", "profile" to "archive"), mapOf("unknown" to true))) {
                val rejected = assertThrows(GenerationException::class.java) { registry.select(requirements(DocumentFormat.PDF, options)) }
                assertTrue(rejected.diagnostics.any { it.code == DiagnosticCodes.UNSUPPORTED_OPTION })
            }
            System.setProperty("nuaforma.fixture.pdf.SelectA", "false")
            try {
                assertEquals("selectb", registry.select(requirements(DocumentFormat.PDF)).metadata.id)
                assertEquals("selecta", registry.select(requirements(), "selecta").metadata.id)
                val unavailable = assertThrows(GenerationException::class.java) { registry.select(requirements(DocumentFormat.PDF), "selecta") }
                assertTrue(unavailable.diagnostics.any { it.code == DiagnosticCodes.PROVIDER_UNAVAILABLE })
            } finally { System.clearProperty("nuaforma.fixture.pdf.SelectA") }
        }
    }

    @Test
    fun `no providers no matching formats and explicit registration are supported`() {
        val empty = ProviderRegistry.registered(emptyList())
        assertEquals("NO_MATCHING_PROVIDER", assertThrows(GenerationException::class.java) { empty.select(requirements()) }.diagnostics.first().code.id)
        val fixtures = PackagedProviders(directory)
        fixtures.loader(fixtures.jar("Explicit", service = null)).use { loader ->
            assertTrue(ProviderRegistry.discover(loader).providers.isEmpty())
            val metadata = ProviderMetadata("explicit", "independent.Explicit", "1.0.0", ContractVersion.CURRENT)
            var calls = 0
            val registration = ProviderRegistration(metadata) { calls++; loader.loadClass(metadata.implementationClass).getConstructor().newInstance() as DocumentProvider }
            val registry = ProviderRegistry.registered(listOf(registration))
            assertEquals(1, calls)
            assertEquals("explicit", registry.select(requirements()).metadata.id)
            val unmatched = GenerationRequirements(FormatRequest(DocumentFormat("custom")), FormatRequest(DocumentFormat.PDF))
            assertThrows(GenerationException::class.java) { registry.select(unmatched) }
            val rejected = ProviderRegistration(ProviderMetadata("future", metadata.implementationClass, "1.0.0", ContractVersion(2, 0))) { calls++; error("Must not execute") }
            assertTrue(ProviderRegistry.registered(listOf(rejected)).providers.isEmpty())
            assertEquals(1, calls)
        }
    }

    @Test
    fun `catalog generates isolated requests concurrently and honors cancellation without closing caller streams`() {
        val fixtures = PackagedProviders(directory)
        fixtures.loader(fixtures.jar("Concurrent")).use { loader ->
            val registry = ProviderRegistry.discover(loader)
            val pool = Executors.newFixedThreadPool(4)
            try {
                val jobs = (1..20).map { index -> pool.submit<String> {
                    val output = ByteArrayOutputStream()
                    registry.generate(GenerationRequest(ByteArrayInputStream(byteArrayOf()), requirements(), mapOf("reference" to "NF-$index")), output, GenerationContext())
                    output.toString(Charsets.UTF_8)
                } }
                assertEquals((1..20).map { "NF-$it" }, jobs.map { it.get(10, TimeUnit.SECONDS) })
            } finally { pool.shutdownNow() }
            val input = object : ByteArrayInputStream(byteArrayOf()) { var closed = false; override fun close() { closed = true } }
            val output = object : ByteArrayOutputStream() { var closed = false; override fun close() { closed = true } }
            val request = GenerationRequest(input, requirements())
            registry.generate(request, output, GenerationContext())
            assertFalse(input.closed)
            assertFalse(output.closed)
            output.reset()
            assertThrows(GenerationException::class.java) { registry.generate(request, output, GenerationContext(cancellation = CancellationToken { true })) }
            assertEquals(0, output.size())
            assertFalse(input.closed)
            assertFalse(output.closed)
        }
    }

    @Test
    fun `class identity registration and initialization failures are isolated and sanitized`() {
        val fixtures = PackagedProviders(directory)
        val nonProvider = fixtures.jar("NotAProvider", sourceTransform = { it.replace("implements DocumentProvider", "") })
        val failing = fixtures.jar("Faulty", sourceTransform = {
            it.replace("static { System.setProperty", "static { if (Boolean.parseBoolean(\"true\")) throw new IllegalStateException(\"SECRET_NEVER_LOG\"); System.setProperty")
        })
        fixtures.loader(nonProvider, failing, fixtures.jar("Working")).use { loader ->
            val registry = ProviderRegistry.discover(loader)
            assertEquals("working", registry.select(requirements()).metadata.id)
            assertEquals(2, registry.diagnostics.size)
            assertTrue(registry.diagnostics.all { it.code.id == "INVALID_PROVIDER" && !it.message.contains("SECRET_NEVER_LOG") })
            assertNull(System.getProperty("nuaforma.fixture.initialized.NotAProvider"))
        }
    }

    @Test
    fun `unexpected result format fails rather than silently downgrading`() {
        val fixtures = PackagedProviders(directory)
        val wrong = fixtures.jar("WrongResult", sourceTransform = {
            it.replace("new GenerationResult(request.getRequirements().getOutput())", "new GenerationResult(new FormatRequest(DocumentFormat.PDF))")
        })
        fixtures.loader(wrong).use { loader ->
            val registry = ProviderRegistry.discover(loader)
            val output = ByteArrayOutputStream()
            val error = assertThrows(GenerationException::class.java) {
                registry.generate(GenerationRequest(ByteArrayInputStream(byteArrayOf()), requirements()), output, GenerationContext())
            }
            assertEquals("INVALID_PROVIDER_RESULT", error.diagnostics.single().code.id)
            assertTrue(output.size() > 0) // Caller-owned streams cannot be rolled back.
        }
    }

    @Test
    fun `exploded local class directory uses the same metadata checks`() {
        val fixtures = PackagedProviders(directory)
        val classes = fixtures.unpack(fixtures.jar("Exploded", service = "# A comment\nindependent.Exploded # inline comment\nindependent.Exploded\n"))
        fixtures.loader(classes).use { loader ->
            val registry = ProviderRegistry.discover(loader)
            assertTrue(registry.diagnostics.isEmpty())
            assertEquals("exploded", registry.select(requirements()).metadata.id)
        }
    }

    @Test
    fun `discovery does not open remote service resources`() {
        var connections = 0
        val remote = java.net.URL(null, "https://invalid.example/META-INF/services/dev.nuaforma.contracts.DocumentProvider", object : java.net.URLStreamHandler() {
            override fun openConnection(url: java.net.URL): java.net.URLConnection { connections++; error("Must not open") }
        })
        val loader = object : ClassLoader(DocumentProvider::class.java.classLoader) {
            override fun getResources(name: String): java.util.Enumeration<java.net.URL> = java.util.Collections.enumeration(listOf(remote))
        }
        val registry = ProviderRegistry.discover(loader)
        assertTrue(registry.providers.isEmpty())
        assertTrue(registry.diagnostics.isNotEmpty())
        assertEquals(0, connections)
    }
}
