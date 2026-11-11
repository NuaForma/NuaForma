package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.ownengine.WordLayoutOracle
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import dev.nuaforma.pdf.PdfProcessValidator
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO

class NativeInitialSectionMetadataTest {
    @TempDir lateinit var temporary: Path
    private data class Case(val name: String, val marker: Regex, val count: Int, val reused: String? = null)
    private val cases = listOf(
        Case("single-a4", Regex("([KST][0-9]{2})\\b"), 60, "theme-colors/a4"),
        Case("single-letter", Regex("([KST][0-9]{2})\\b"), 60, "theme-colors/letter"),
        Case("multi-initial", Regex("(Q0[1-4])\\b"), 4))

    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes() }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun provider(): DocxPdfProvider {
        val fonts = listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic", "boldItalic" to "BoldItalic").map { (face, name) ->
            NativePdfFont("Liberation Sans", face, resource("/own-engine-fonts/LiberationSans-$name.ttf"))
        }
        return DocxPdfProvider(NativePdfConfiguration(fonts, PdfProcessValidator(
            Path.of(System.getProperty("nuaforma.pdf.validation.java")),
            Files.list(Path.of(System.getProperty("nuaforma.pdf.validation"))).use { paths -> paths.filter { it.toString().endsWith(".jar") }.sorted().toList() }, temporary)))
    }

    private fun request(bytes: ByteArray, profile: String = "standard") = GenerationRequest(bytes.inputStream(),
        GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile))))

    private fun text(pdf: org.apache.pdfbox.pdmodel.PDDocument, page: Int) = PDFTextStripper().also { it.startPage = page; it.endPage = page }
        .getText(pdf).replace(Regex("\\s+"), " ").trim()

    @Test
    fun `initial section markers and enforced form protection preserve qualified Word pages`() {
        val provider = provider()
        val output = Files.createDirectories(Path.of("build/246-initial-sections"))
        for (case in cases) {
            val base = "/initial-section-metadata/${case.name}/"
            val source = resource(base + "source.docx")
            val reference = resource(base + "word-reference.pdf")
            val receipt = resource(base + "word-reference.pdf.provenance.json").decodeToString()
            assertTrue(receipt.contains(hash(source)) && receipt.contains(hash(reference)))
            val goldenReceipt = resource(base + "golden-provenance.json").decodeToString()
            assertTrue(goldenReceipt.contains(hash(source)) && goldenReceipt.contains(hash(reference)))
            case.reused?.let { assertTrue(goldenReceipt.contains(hash(resource("/$it/source.docx")))) }
            val cleaned = ByteArrayOutputStream()
            assertTrue(provider.cleanTemplate(source.inputStream(), cleaned,
                GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))).changes.isEmpty())
            assertArrayEquals(source, cleaned.toByteArray())
            Loader.loadPDF(reference).use { word ->
                val expected = WordLayoutOracle.positions(word, case.marker)
                assertEquals(case.count, expected.size)
                assertEquals(2, word.numberOfPages)
                for (profile in listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b")) {
                    val rendered = ByteArrayOutputStream()
                    provider.generate(request(source, profile), rendered, GenerationContext())
                    Files.write(output.resolve("${case.name}-$profile.pdf"), rendered.toByteArray())
                    Loader.loadPDF(rendered.toByteArray()).use { actual ->
                        assertEquals(word.numberOfPages, actual.numberOfPages)
                        val positions = WordLayoutOracle.positions(actual, case.marker)
                        assertEquals(expected.keys, positions.keys)
                        for ((marker, location) in expected) {
                            val found = positions.getValue(marker)
                            assertEquals(location.page, found.page, "${case.name} $marker page")
                            assertEquals(location.x, found.x, .3f, "${case.name} $marker x")
                            assertEquals(location.y, found.y, 1.5f, "${case.name} $marker baseline")
                            assertEquals(location.face, found.face, "${case.name} $marker face")
                            assertEquals(location.size, found.size, .05f, "${case.name} $marker size")
                            val transformed = marker in setOf("K12", "K13", "K18", "K19", "K24", "K25", "K30", "K31", "K32", "S02") || marker.startsWith("T")
                            val tolerance = if (transformed) 1 else 0
                            for (shift in listOf(0, 8, 16)) assertTrue(kotlin.math.abs((location.rgb shr shift and 255) -
                                (found.rgb shr shift and 255)) <= tolerance, "${case.name} $marker color")
                        }
                        if (case.reused != null) WordLayoutOracle.verifyTableFills(source, word, actual, 1, 15f)
                        for (page in 0 until actual.numberOfPages) {
                            assertEquals(text(word, page + 1), text(actual, page + 1))
                            assertEquals(word.getPage(page).mediaBox.width, actual.getPage(page).mediaBox.width, .1f)
                            assertEquals(word.getPage(page).mediaBox.height, actual.getPage(page).mediaBox.height, .1f)
                            assertTrue(actual.getPage(page).resources.fontNames.all { actual.getPage(page).resources.getFont(it).isEmbedded })
                            val goldenBase = case.reused?.let { "/$it/" } ?: base
                            val golden = resource(goldenBase + "page-${page + 1}.png")
                            assertTrue(goldenReceipt.contains(hash(golden)))
                            val raster = PDFRenderer(actual).renderImageWithDPI(page, 96f)
                            assertImagesMatch(ImageIO.read(golden.inputStream()), raster) {
                                pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                            }
                            ImageIO.write(raster, "png", output.resolve("${case.name}-$profile-page-${page + 1}.png").toFile())
                        }
                    }
                }
            }
        }
    }

    private fun changed(name: String, transform: (String) -> String): ByteArray {
        val parts = PdfFixture.unzip(resource("/initial-section-metadata/$name/source.docx"))
        val before = parts.getValue("word/document.xml").decodeToString()
        val after = transform(before)
        require(before != after)
        return PdfFixture.zip(parts.mapValues { (part, bytes) -> if (part == "word/document.xml") after.toByteArray() else bytes })
    }

    @Test
    fun `genuine unsupported transitions and malformed original settings reject without partial output`() {
        val provider = provider()
        val variants = listOf(
            changed("multi-initial") { it.replace("w:val=\"nextPage\"", "w:val=\"continuous\"") },
            changed("single-a4") { it.replace("w:val=\"continuous\"", "w:val=\"oddPage\"") },
            changed("single-a4") { it.replace("w:val=\"continuous\"", "w:val=\"evenPage\"") },
            changed("single-a4") { it.replace("w:val=\"continuous\"", "w:val=\"nextColumn\"") },
            changed("single-a4") { it.replace("w:val=\"continuous\"", "w:val=\"unknown\"") },
            changed("single-a4") { it.replace("<w:type w:val=\"continuous\"/>", "<w:type w:val=\"continuous\" extra=\"1\"/>") },
            changed("single-a4") { it.replace("<w:type w:val=\"continuous\"/>", "<w:type w:val=\"continuous\"/><w:type w:val=\"nextPage\"/>") },
            changed("single-letter") { it.replace("<w:formProt/>", "<w:formProt extra=\"1\"/>") },
            changed("single-letter") { it.replace("<w:formProt/>", "<w:formProt w:val=\"yes\"/>") },
            changed("single-letter") { it.replace("<w:formProt/>", "<w:formProt/><w:formProt w:val=\"0\"/>") })
        for (source in variants) {
            val output = ByteArrayOutputStream()
            val error = assertThrows(GenerationException::class.java) {
                provider.generate(request(source), output, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
            }
            assertEquals(0, output.size())
            assertTrue(error.diagnostics.any { it.code == DiagnosticCodes.UNSUPPORTED_INPUT && it.part == "word/document.xml" })
        }
    }
}
