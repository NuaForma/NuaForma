package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.ownengine.WordLayoutOracle
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import dev.nuaforma.pdf.PdfProcessValidator
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
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

class NativeWordCompatibilityModeTest {
    @TempDir lateinit var temporary: Path
    private data class Case(val name: String, val marker: Regex, val legacyGoldens: String? = null)
    private val cases = listOf(
        Case("spacing-a4", Regex("([ABSCMDPT][0-9]{2,3})\\b"), "paragraph-spacing/legacy-fixed-edges"),
        Case("spacing-letter", Regex("([ABSCMDPT][0-9]{2,3})\\b")),
        Case("table-legacy", Regex("(C[0-5][0-3])\\b"), "conditional-tables/word12-legacy"),
        Case("table-override", Regex("(C[0-5][0-3])\\b"), "conditional-tables/word12-override"),
        Case("table-letter", Regex("(C[0-5][0-3])\\b")),
        Case("mirror-letter", Regex("([MNRF][1-4]0)\\b")),
        Case("font-open-type", Regex("(F[0-9]{2})\\b"), "font-features/legacy-enabled"))

    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes() }

    private fun provider(): DocxPdfProvider {
        val fonts = listOf(NativePdfFont("Noto Serif", "regular", resource("/own-engine-fonts/NotoSerif-Regular.ttf"))) +
            listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic", "boldItalic" to "BoldItalic").map { (face, name) ->
                NativePdfFont("Liberation Sans", face, resource("/own-engine-fonts/LiberationSans-$name.ttf"))
            }
        return DocxPdfProvider(NativePdfConfiguration(fonts, PdfProcessValidator(
            Path.of(System.getProperty("nuaforma.pdf.validation.java")),
            Files.list(Path.of(System.getProperty("nuaforma.pdf.validation"))).use { paths -> paths.filter { it.toString().endsWith(".jar") }.sorted().toList() }, temporary)))
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun text(pdf: PDDocument, page: Int): String = PDFTextStripper().also {
        it.startPage = page; it.endPage = page
    }.getText(pdf).replace(Regex("\\s+"), " ").trim()

    @Test
    fun `Word 2010 formatting matches independent references across all PDF profiles`() {
        val provider = provider()
        val output = Files.createDirectories(Path.of("build/244-compatibility-mode"))
        for (case in cases) {
            val base = "/compatibility-mode-14/${case.name}/"
            val source = resource(base + "source.docx")
            val reference = resource(base + "word-reference.pdf")
            val receipt = resource(base + "word-reference.pdf.provenance.json").decodeToString()
            assertTrue(receipt.contains(hash(source)) && receipt.contains(hash(reference)))
            val goldenReceipt = resource(base + "golden-provenance.json").decodeToString()
            assertTrue(goldenReceipt.contains(hash(source)) && goldenReceipt.contains(hash(reference)))
            case.legacyGoldens?.let { assertTrue(goldenReceipt.contains(hash(resource("/$it/source.docx")))) }
            val cleaned = ByteArrayOutputStream()
            assertTrue(provider.cleanTemplate(source.inputStream(), cleaned,
                GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))).changes.isEmpty())
            assertArrayEquals(source, cleaned.toByteArray())
            Loader.loadPDF(reference).use { word ->
                val expected = WordLayoutOracle.positions(word, case.marker)
                assertTrue(expected.isNotEmpty(), case.name)
                for (profile in listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b")) {
                    val rendered = ByteArrayOutputStream()
                    provider.generate(GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                        FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile)))), rendered, GenerationContext())
                    val bytes = rendered.toByteArray()
                    Files.write(output.resolve("${case.name}-$profile.pdf"), bytes)
                    Loader.loadPDF(bytes).use { actual ->
                        assertEquals(word.numberOfPages, actual.numberOfPages, case.name)
                        val candidate = WordLayoutOracle.positions(actual, case.marker)
                        assertEquals(expected.keys, candidate.keys, "${case.name} $profile marker set")
                        for ((marker, location) in expected) {
                            val found = candidate.getValue(marker)
                            assertEquals(location.page, found.page, "${case.name} $marker page")
                            assertEquals(location.x, found.x, .3f, "${case.name} $marker x")
                            assertEquals(location.y, found.y, 1.5f, "${case.name} $marker baseline")
                            assertEquals(location.size, found.size, .05f, "${case.name} $marker size")
                            assertEquals(location.face, found.face, "${case.name} $marker face")
                            assertEquals(location.rgb, found.rgb, "${case.name} $marker color")
                        }
                        if (case.name.startsWith("spacing-")) {
                            for ((before, after) in listOf("A00" to "B00", "B00" to "A01", "A02" to "B02",
                                "S01" to "S02", "S02" to "S03", "C01" to "C02", "D01" to "D02", "M01" to "M02",
                                "P01" to "T000", "T000" to "T001", "T002" to "T100", "T102" to "P02"))
                                assertEquals(expected.getValue(after).y - expected.getValue(before).y,
                                    candidate.getValue(after).y - candidate.getValue(before).y, .3f, "${case.name} $before to $after gap")
                        }
                        if (case.name.startsWith("table-")) WordLayoutOracle.verifyTableFills(source, word, actual)
                        for (page in 0 until actual.numberOfPages) {
                            assertEquals(text(word, page + 1), text(actual, page + 1), "${case.name} $profile page $page text")
                            assertEquals(word.getPage(page).mediaBox.width, actual.getPage(page).mediaBox.width, .1f)
                            assertEquals(word.getPage(page).mediaBox.height, actual.getPage(page).mediaBox.height, .1f)
                            assertTrue(actual.getPage(page).resources.fontNames.all { actual.getPage(page).resources.getFont(it).isEmbedded })
                            val goldenBase = case.legacyGoldens?.let { "/$it/" } ?: base
                            val golden = resource(goldenBase + "page-${page + 1}.png")
                            assertTrue(goldenReceipt.contains(hash(golden)))
                            val raster = PDFRenderer(actual).renderImageWithDPI(page, 96f)
                            assertImagesMatch(ImageIO.read(golden.inputStream()), raster) {
                                pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                            }
                            ImageIO.write(raster, "png",
                                output.resolve("${case.name}-$profile-page-${page + 1}.png").toFile())
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `invalid original settings are attributed before serialization without publishing a partial PDF`() {
        val provider = provider()
        val original = PdfFixture.unzip(resource("/compatibility-mode-14/table-legacy/source.docx"))
        val settings = original.getValue("word/settings.xml").decodeToString()
        val duplicate = "<w:compatSetting w:name=\"compatibilityMode\" w:uri=\"http://schemas.microsoft.com/office/word\" w:val=\"15\"/>"
        for (changed in listOf(settings.replace("w:val=\"14\"", "w:val=\"16\""),
            settings.replace("w:val=\"14\"", "w:val=\"14\" extra=\"1\""),
            settings.replace("</w:settings>", "<w:compat/></w:settings>"),
            settings.replace("</w:compat>", "$duplicate</w:compat>"))) {
            val parts = original.mapValues { (part, bytes) -> if (part == "word/settings.xml") changed.toByteArray() else bytes }
            val output = ByteArrayOutputStream()
            val error = assertThrows(GenerationException::class.java) {
                provider.generate(GenerationRequest(PdfFixture.zip(parts).inputStream(), GenerationRequirements(
                    FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.PDF))), output,
                    GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
            }
            assertEquals(0, output.size())
            assertTrue(error.diagnostics.any { it.code == DiagnosticCodes.UNSUPPORTED_INPUT && it.part == "word/settings.xml" })
        }
    }

}
