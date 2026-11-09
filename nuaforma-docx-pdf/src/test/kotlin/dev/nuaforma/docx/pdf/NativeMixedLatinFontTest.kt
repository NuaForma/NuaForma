package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.ownengine.WordLayoutOracle
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import dev.nuaforma.pdf.PdfProcessValidator
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO

class NativeMixedLatinFontTest {
    @TempDir lateinit var temporary: Path
    private val markers = Regex("((?:R[0-9]{2}|É(?:0[2-9]|1[0-9]|20)|[ΩЖ]0[12]))\\b")

    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes() }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun fonts() = listOf("Liberation Sans" to "LiberationSans-Regular.ttf", "Noto Serif" to "NotoSerif-Regular.ttf")
        .map { (family, filename) -> NativePdfFont(family, "regular", resource("/own-engine-fonts/$filename")) }

    private fun provider() = DocxPdfProvider(NativePdfConfiguration(fonts(), PdfProcessValidator(
        Path.of(System.getProperty("nuaforma.pdf.validation.java")),
        Files.list(Path.of(System.getProperty("nuaforma.pdf.validation"))).use { paths -> paths.filter { it.toString().endsWith(".jar") }.sorted().toList() }, temporary)))

    private fun request(bytes: ByteArray, profile: String = "standard") = GenerationRequest(bytes.inputStream(),
        GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile))))

    private fun text(pdf: org.apache.pdfbox.pdmodel.PDDocument, page: Int) = PDFTextStripper().also { it.startPage = page; it.endPage = page }
        .getText(pdf).replace(Regex("\\s+"), " ").trim()

    @Test
    fun `mixed Latin font roles preserve independent Word pages and all archival profiles`() {
        val provider = provider()
        val output = Files.createDirectories(Path.of("build/248-mixed-latin-fonts"))
        for (name in listOf("a4", "letter")) {
            val base = "/mixed-latin-fonts/$name/"
            val source = resource(base + "source.docx")
            val reference = resource(base + "word-reference.pdf")
            val provenance = resource(base + "word-reference.pdf.provenance.json").decodeToString()
            val goldenProvenance = resource(base + "golden-provenance.json").decodeToString()
            assertTrue(provenance.contains(hash(source)) && provenance.contains(hash(reference)))
            assertTrue(goldenProvenance.contains(hash(source)) && goldenProvenance.contains(hash(reference)))
            for (filename in listOf("LiberationSans-Regular.ttf", "NotoSerif-Regular.ttf")) {
                assertTrue(provenance.contains(hash(resource("/own-engine-fonts/$filename"))))
            }
            val cleaned = ByteArrayOutputStream()
            assertTrue(provider.cleanTemplate(source.inputStream(), cleaned,
                GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))).changes.isEmpty())
            assertArrayEquals(source, cleaned.toByteArray())
            Loader.loadPDF(reference).use { word ->
                val expected = WordLayoutOracle.positions(word, markers)
                assertEquals(42, expected.size)
                assertEquals(2, word.numberOfPages)
                for (profile in listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b")) {
                    val rendered = ByteArrayOutputStream()
                    provider.generate(request(source, profile), rendered, GenerationContext())
                    Files.write(output.resolve("$name-$profile.pdf"), rendered.toByteArray())
                    Loader.loadPDF(rendered.toByteArray()).use { actual ->
                        assertEquals(word.numberOfPages, actual.numberOfPages)
                        val found = WordLayoutOracle.positions(actual, markers)
                        assertEquals(expected.keys, found.keys)
                        for ((marker, location) in expected) {
                            val position = found.getValue(marker)
                            assertEquals(location.page, position.page, marker)
                            assertEquals(location.face, position.face, marker)
                            assertEquals(location.x, position.x, .3f, marker)
                            assertEquals(location.y, position.y, .2f, marker)
                            assertEquals(location.size, position.size, .05f, marker)
                            assertEquals(location.rgb, position.rgb, marker)
                        }
                        for (page in 0 until word.numberOfPages) {
                            assertEquals(text(word, page + 1), text(actual, page + 1))
                            assertEquals(word.getPage(page).mediaBox.width, actual.getPage(page).mediaBox.width, .1f)
                            assertEquals(word.getPage(page).mediaBox.height, actual.getPage(page).mediaBox.height, .1f)
                            assertTrue(actual.getPage(page).resources.fontNames.all { actual.getPage(page).resources.getFont(it).isEmbedded })
                            val golden = resource(base + "page-${page + 1}.png")
                            assertTrue(goldenProvenance.contains(hash(golden)))
                            val raster = PDFRenderer(actual).renderImageWithDPI(page, 96f)
                            assertImagesMatch(ImageIO.read(golden.inputStream()), raster) {
                                pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                            }
                        }
                        val links = actual.getPage(0).annotations.filterIsInstance<PDAnnotationLink>()
                        assertTrue(links.isNotEmpty())
                        val destinations = links.map { link ->
                            val destination = link.destination ?: (link.action as? PDActionGoTo)?.destination
                            val named = assertInstanceOf(PDNamedDestination::class.java, destination)
                            val target = actual.documentCatalog.findNamedDestinationPage(named)
                            assertNotNull(target)
                            assertEquals(0, target!!.retrievePageNumber())
                            named.namedDestination
                        }
                        assertEquals(1, destinations.distinct().size)
                    }
                }
            }
        }
    }

    private fun changedFont(replacement: String, text: String? = null): ByteArray {
        val parts = PdfFixture.unzip(resource("/mixed-latin-fonts/a4/source.docx"))
        val original = parts.getValue("word/document.xml").decodeToString()
        var changed = original.replaceFirst("<w:rFonts w:ascii=\"Noto Serif\"/>", replacement)
        text?.let { changed = changed.replace("R01 ASCII serif; É01 élève € Ω01 Ελληνικά Ж01 Отчёт.", it) }
        require(changed != original)
        return PdfFixture.zip(parts.mapValues { (name, bytes) -> if (name == "word/document.xml") changed.toByteArray() else bytes })
    }

    @Test
    fun `original malformed font properties and unqualified ranges reject before partial publication`() {
        val provider = provider()
        val cases = listOf(
            changedFont("<w:rFonts w:ascii=\"Noto Serif\" extra=\"1\"/>"),
            changedFont("<w:rFonts w:ascii=\"Noto Serif\"/><w:rFonts w:ascii=\"Liberation Sans\" w:hAnsi=\"Liberation Sans\"/>"),
            changedFont("<w:rFonts w:asciiTheme=\"unknown\"/>"),
            changedFont("<w:rFonts w:ascii=\"Noto Serif\"/>", "中"),
            changedFont("<w:rFonts w:ascii=\"Noto Serif\"/>", "E\u0301"),
            changedFont("<w:rFonts w:ascii=\"Noto Serif\"/>", "aé".repeat(501)))
        for (source in cases) {
            val output = ByteArrayOutputStream()
            val error = assertThrows(GenerationException::class.java) {
                provider.generate(request(source), output, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
            }
            assertEquals(0, output.size())
            assertTrue(error.diagnostics.any { it.code == DiagnosticCodes.UNSUPPORTED_INPUT && it.part == "word/document.xml" })
        }
    }
}
