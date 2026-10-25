package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.DocxProvider
import dev.nuaforma.pdf.PdfFontEmbedding
import dev.nuaforma.pdf.PdfProcessValidator
import dev.nuaforma.template.TemplatePipes
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.ResourceLock
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.io.ByteArrayOutputStream

@ResourceLock("java.lang.System.properties")
class NativePdfConfigurationTest {
    @TempDir lateinit var temporary: Path
    private fun bytes() = requireNotNull(javaClass.getResourceAsStream("/own-engine-fonts/LiberationSans-Regular.ttf")).use { it.readBytes() }
    private val keys = listOf("nuaforma.pdf.pdfaValidation", "nuaforma.pdf.parallelism", "nuaforma.pdf.validator.java",
        "nuaforma.pdf.validator.directory", "nuaforma.pdf.validator.launcher", "nuaforma.fonts.directory", "nuaforma.fonts.bundledDirectories")
    private fun settings(values: Map<String, String> = emptyMap(), body: () -> Unit) {
        val before = keys.associateWith(System::getProperty)
        try {
            keys.forEach(System::clearProperty)
            values.forEach(System::setProperty)
            body()
        } finally { before.forEach { (key, value) -> if (value == null) System.clearProperty(key) else System.setProperty(key, value) } }
    }

    @Test fun `operator policy and concurrency are strict immutable startup settings`() = settings {
        val defaults = NativePdfConfiguration.configured()
        assertTrue(defaults.pdfaValidation)
        assertNull(defaults.validator)
        assertTrue(defaults.fonts.isEmpty())
        assertEquals(1, defaults.admission.availablePermits())
        for (value in listOf("true", "false")) {
            System.setProperty("nuaforma.pdf.pdfaValidation", value)
            System.setProperty("nuaforma.pdf.parallelism", "8")
            val configured = NativePdfConfiguration.configured()
            assertEquals(value == "true", configured.pdfaValidation)
            assertEquals(8, configured.admission.availablePermits())
        }
        for (value in listOf("", "TRUE", "0", "false ", "PRIVATE_SETTING")) {
            System.setProperty("nuaforma.pdf.pdfaValidation", value)
            val failure = assertThrows(IllegalArgumentException::class.java) { NativePdfConfiguration.configured() }
            assertFalse(failure.message.orEmpty().contains("PRIVATE_SETTING"))
        }
        System.clearProperty("nuaforma.pdf.pdfaValidation")
        for (value in listOf("", "0", "9", "-1", "1.0", "private")) {
            System.setProperty("nuaforma.pdf.parallelism", value)
            assertThrows(IllegalArgumentException::class.java) { NativePdfConfiguration.configured() }
        }
        assertTrue(defaults.pdfaValidation)
        assertEquals(1, defaults.admission.availablePermits())
    }

    @Test fun `configured mounted faces override bundled faces and retain full embedding bytes after file changes`() {
        val bundled = Files.createDirectory(temporary.resolve("bundled"))
        val mounted = Files.createDirectory(temporary.resolve("mounted"))
        val original = bytes()
        val full = original.copyOf()
        val buffer = ByteBuffer.wrap(full)
        val table = (0 until (buffer.getShort(4).toInt() and 65535)).first { buffer.getInt(12 + it * 16) == 0x4F532F32 }
        buffer.putShort(buffer.getInt(12 + table * 16 + 8) + 8, 256.toShort())
        Files.write(bundled.resolve("regular.ttf"), original)
        val file = Files.write(mounted.resolve("regular.ttf"), full)
        settings(mapOf("nuaforma.fonts.directory" to mounted.toString(), "nuaforma.fonts.bundledDirectories" to bundled.toString())) {
            val font = NativePdfConfiguration.configured().fonts.single()
            assertEquals(FontOrigin.MOUNTED, font.origin)
            assertEquals(PdfFontEmbedding.FULL, font.embedding)
            assertNotNull(font.resourceId)
            Files.writeString(file, "changed after snapshot")
            assertArrayEquals(full, font.source().openStream().use { it.readBytes() })
        }
        settings(mapOf("nuaforma.fonts.bundledDirectories" to bundled.toString())) {
            val font = NativePdfConfiguration.configured().fonts.single()
            assertEquals(FontOrigin.BUNDLED, font.origin)
            assertEquals(PdfFontEmbedding.SUBSET, font.embedding)
        }
    }

    @Test fun `validator configuration distinguishes absent runtime paths from a complete distribution`() {
        val java = System.getProperty("nuaforma.pdf.validation.java")
        val libraries = System.getProperty("nuaforma.pdf.validation")
        val valid = mapOf("nuaforma.pdf.validator.java" to java, "nuaforma.pdf.validator.directory" to libraries)
        settings(valid) { assertInstanceOf(PdfProcessValidator::class.java, NativePdfConfiguration.configured().validator) }
        val launcher = Files.writeString(temporary.resolve("launcher"), "#!/bin/sh\nexit 1\n")
        check(launcher.toFile().setExecutable(true))
        settings(valid + ("nuaforma.pdf.validator.launcher" to launcher.toString())) {
            assertNotNull(NativePdfConfiguration.configured().validator)
        }
        val absent = temporary.resolve("missing").toString()
        for (properties in listOf(mapOf("nuaforma.pdf.validator.directory" to libraries), mapOf("nuaforma.pdf.validator.java" to java),
            valid + ("nuaforma.pdf.validator.java" to absent), valid + ("nuaforma.pdf.validator.java" to temporary.toString()),
            valid + ("nuaforma.pdf.validator.directory" to absent), valid + ("nuaforma.pdf.validator.launcher" to absent))) {
            settings(properties) { assertNull(NativePdfConfiguration.configured().validator) }
        }
        settings(valid + ("nuaforma.pdf.validator.directory" to temporary.toString())) {
            assertThrows(IllegalArgumentException::class.java) { NativePdfConfiguration.configured() }
        }
    }

    @Test fun `font identity budgets and immutable catalogs reject unsafe or ambiguous configuration`() {
        val content = bytes()
        val font = NativePdfFont("Liberation Sans", "regular", content)
        val expected = content.copyOf()
        content.fill(0)
        assertArrayEquals(expected, font.source().openStream().use { it.readBytes() })
        for ((family, face, id) in listOf(Triple("Wrong family", "regular", null), Triple("Liberation Sans", "bold", null),
            Triple("Liberation Sans", "regular", "../private"))) {
            assertThrows(IllegalArgumentException::class.java) { NativePdfFont(family, face, expected, resourceId = id) }
        }
        assertThrows(IllegalArgumentException::class.java) { NativePdfFont("Liberation Sans", "regular", byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { NativePdfFont("Liberation Sans", "regular", ByteArray(8_388_609)) }
        for (count in listOf(0, 65)) assertThrows(IllegalArgumentException::class.java) { NativePdfConfiguration(emptyList(), maximumConcurrentConversions = count) }
        assertThrows(IllegalArgumentException::class.java) { NativePdfConfiguration(listOf(font, font)) }
        assertThrows(IllegalArgumentException::class.java) { NativePdfConfiguration(List(257) { font }) }
        val oversized = NativePdfFont("Liberation Sans", "regular", expected.copyOf(8_388_608))
        assertThrows(IllegalArgumentException::class.java) { NativePdfConfiguration(List(17) { oversized }) }
        val mutable = mutableListOf(font)
        val captured = NativePdfConfiguration(mutable)
        mutable.clear()
        assertEquals(1, captured.fonts.size)
        assertThrows(UnsupportedOperationException::class.java) { (captured.fonts as MutableList).clear() }
    }

    @Test fun `registered facade constructors preserve configuration facets assessment and DOCX generation`() = settings {
        val explicit = NativePdfConfiguration(emptyList(), pdfaValidation = false)
        val providers = listOf(DocxPdfProvider(), DocxPdfProvider(null), DocxPdfProvider(explicit),
            DocxPdfProvider(null, emptyList()), DocxPdfProvider(explicit, emptyList()),
            DocxPdfProvider(null, emptyList(), TemplatePipes.discover()), DocxPdfProvider(explicit, emptyList(), TemplatePipes.discover()))
        val request = GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.DOCX))
        providers.forEach { provider ->
            val context = GenerationContext()
            assertTrue(provider.capabilities().writeFormats.contains(DocumentFormat.PDF))
            assertEquals(setOf(DocumentFormat.DOCX), provider.availability().writeFormats)
            assertEquals(setOf(DocumentFormat.DOCX), provider.availability(context).writeFormats)
            assertEquals(AssessmentStatus.SUPPORTED, provider.assess(request).status)
            assertEquals(AssessmentStatus.SUPPORTED, provider.assess(request, context).status)
            assertTrue(provider.fontCatalog(context).fonts.isEmpty())
            assertEquals(1, provider.conformancePolicies().size)
            assertEquals(DocxProvider().templatePipes().map { it.name }, provider.templatePipes().map { it.name })
            assertTrue(provider.contentPipes().any { it.name == "image" })
        }
        assertFalse(providers[2].conformancePolicies().single().validationEnabled)
        val output = ByteArrayOutputStream()
        requireNotNull(javaClass.getResourceAsStream("/own-engine/source.docx")).use { source ->
            providers[2].generate(GenerationRequest(source, request), output, GenerationContext())
        }
        assertTrue(output.size() > 0)
        java.util.zip.ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            var document: String? = null
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "word/document.xml") document = zip.readBytes().decodeToString()
            }
            assertTrue(requireNotNull(document).contains("Bevestiging van uw aanvraag"))
        }
        val cancelled = GenerationContext(cancellation = CancellationToken { true })
        assertEquals(DiagnosticCodes.CANCELLED, assertThrows(GenerationException::class.java) { providers[2].availability(cancelled) }.diagnostics.single().code)
    }
}
