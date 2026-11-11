package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.pdf.PdfProcessValidator
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import javax.imageio.ImageIO

class NativeOfficePackageTest {
    @TempDir lateinit var temporary: Path

    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes() }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun provider(fonts: Boolean = true): DocxPdfProvider {
        val catalog = if (fonts) listOf(NativePdfFont("Liberation Sans", "regular", resource("/own-engine-fonts/LiberationSans-Regular.ttf"))) else emptyList()
        val validator = PdfProcessValidator(Path.of(System.getProperty("nuaforma.pdf.validation.java")),
            Files.list(Path.of(System.getProperty("nuaforma.pdf.validation"))).use { paths -> paths.filter { it.toString().endsWith(".jar") }.sorted().toList() }, temporary)
        return DocxPdfProvider(NativePdfConfiguration(catalog, validator))
    }

    private fun generate(provider: DocxPdfProvider, source: ByteArray, profile: String): ByteArray {
        val output = ByteArrayOutputStream()
        try {
            provider.generate(GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile)))), output,
                GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
        } catch (error: GenerationException) {
            throw AssertionError("$profile: ${error.diagnostics}", error)
        }
        return output.toByteArray()
    }

    private fun glyphs(pdf: PDDocument): List<Triple<String, Float, Float>> {
        val result = mutableListOf<Triple<String, Float, Float>>()
        object : PDFTextStripper() {

            override fun processTextPosition(position: TextPosition) {
                if (position.unicode.isNotBlank()) result += Triple(position.unicode, position.xDirAdj, position.yDirAdj)
                super.processTextPosition(position)
            }

        }.getText(pdf)
        return result.sortedWith(compareBy({ it.third }, { it.second }))
    }

    @Test
    fun `actual Word exports retained source bytes and existing goldens match every validated PDF profile`() {
        val provider = provider()
        val directory = Files.createDirectories(Path.of("build/233-office-package"))
        for ((name, baseline) in listOf("a4" to "trailing-1", "letter" to "bands-letter")) {
            val base = "/passive-office-package/$name/"
            val input = resource(base + "source.docx")
            val reference = resource(base + "word-reference.pdf")
            val provenance = resource(base + "word-reference.pdf.provenance.json").decodeToString()
            assertTrue(provenance.contains("\"sourceSha256\": \"${hash(input)}\""))
            assertTrue(provenance.contains("\"pdfSha256\": \"${hash(reference)}\""))
            val cleaned = ByteArrayOutputStream()
            assertTrue(provider.cleanTemplate(input.inputStream(), cleaned, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))).changes.isEmpty())
            assertArrayEquals(input, cleaned.toByteArray())
            Loader.loadPDF(reference).use { word ->
                val expected = glyphs(word)
                for (profile in listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b")) {
                    val bytes = generate(provider, input, profile)
                    Files.write(directory.resolve("$name-$profile.pdf"), bytes)
                    Loader.loadPDF(bytes).use { actual ->
                        assertEquals(word.numberOfPages, actual.numberOfPages)
                        val positions = glyphs(actual)
                        assertEquals(expected.map { it.first }, positions.map { it.first })
                        for (index in expected.indices) {
                            assertEquals(expected[index].second, positions[index].second, .5f, "$name glyph $index x")
                            assertEquals(expected[index].third, positions[index].third, .25f, "$name glyph $index baseline")
                        }
                        assertTrue(actual.pages.all { page -> page.resources.fontNames.all { page.resources.getFont(it).isEmbedded } })
                        assertImagesMatch(ImageIO.read(resource("/typography-preferences/$baseline/page-1.png").inputStream()), PDFRenderer(actual).renderImageWithDPI(0, 96f)) {
                            pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `schema and form metadata cannot trigger network retrieval`() {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> requests.incrementAndGet(); exchange.sendResponseHeaders(500, -1); exchange.close() }
        server.start()
        try {
            val parts = PdfFixture.unzip(resource("/passive-office-package/a4/source.docx")).toMutableMap()
            val item = "customXml/item1.xml"
            val uri = "http://127.0.0.1:${server.address.port}/schema.xsd"
            parts[item] = parts.getValue(item).decodeToString().replace("<xs:element", "<xs:import namespace=\"urn:never\" schemaLocation=\"$uri\"/><xs:element").toByteArray()
            assertTrue(generate(provider(), PdfFixture.zip(parts), "standard").isNotEmpty())
            assertEquals(0, requests.get())
        } finally { server.stop(0) }
    }

    @Test
    fun `embedded template fonts do not silently satisfy missing caller font requirements`() {
        val output = ByteArrayOutputStream()
        val error = assertThrows(GenerationException::class.java) {
            provider(false).generate(GenerationRequest(resource("/passive-office-package/a4/source.docx").inputStream(),
                GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.PDF, mapOf("profile" to "standard")))), output, GenerationContext())
        }
        assertEquals(0, output.size())
        assertTrue(error.diagnostics.any { it.rejectionReason == RejectionReason.USED_FONT_FACE_UNAVAILABLE })
    }

    @Test
    fun `cleanup of divergent auxiliary styles keeps the ordinary styles and all story text`() {
        val original = PdfFixture.unzip(resource("/passive-office-package/a4/source.docx")).toMutableMap()
        val part = "word/stylesWithEffects.xml"
        original[part] = original.getValue(part).decodeToString().replace("w:val=\"22\"", "w:val=\"26\"").toByteArray()
        val output = ByteArrayOutputStream()
        val report = provider().cleanTemplate(PdfFixture.zip(original).inputStream(), output, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
        val cleaned = PdfFixture.unzip(output.toByteArray())
        assertTrue(report.changes.isNotEmpty())
        assertArrayEquals(original.getValue("word/document.xml"), cleaned.getValue("word/document.xml"))
        assertArrayEquals(original.getValue("word/styles.xml"), cleaned.getValue("word/styles.xml"))
        assertFalse(cleaned.containsKey(part))
        assertTrue(report.changes.none { it.part == "word/document.xml" || it.part == "word/styles.xml" })
    }

}
