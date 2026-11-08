package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.pdf.PdfProcessValidator
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import org.apache.pdfbox.Loader
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
import javax.imageio.ImageIO

class NativeRightIndentAdjustmentTest {
    @TempDir lateinit var temporary: Path

    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes() }

    private fun provider() = DocxPdfProvider(NativePdfConfiguration(listOf(
        NativePdfFont("Liberation Sans", "regular", resource("/own-engine-fonts/LiberationSans-Regular.ttf"))),
        PdfProcessValidator(Path.of(System.getProperty("nuaforma.pdf.validation.java")),
            Files.list(Path.of(System.getProperty("nuaforma.pdf.validation"))).use { files -> files.filter { it.toString().endsWith(".jar") }.sorted().toList() }, temporary)))

    private fun render(provider: DocxPdfProvider, source: ByteArray, profile: String) = ByteArrayOutputStream().also { output ->
        provider.generate(GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
            FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile)))), output, GenerationContext())
    }.toByteArray()

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun glyphs(bytes: ByteArray): List<Triple<String, Float, Float>> {
        val result = mutableListOf<Triple<String, Float, Float>>()
        Loader.loadPDF(bytes).use { pdf ->
            object : PDFTextStripper() {

                override fun processTextPosition(position: TextPosition) {
                    if (position.unicode.isNotBlank()) result += Triple(position.unicode, position.xDirAdj, position.yDirAdj)
                    super.processTextPosition(position)
                }

            }.getText(pdf)
        }
        return result
    }

    @Test
    fun `inactive right-indent adjustment matches real Word on A4 and Letter in all validated profiles`() {
        val provider = provider()
        val directory = Files.createDirectories(Path.of("build/237-right-indent-adjustment"))
        for (name in listOf("a4", "letter")) {
            val base = "/right-indent-adjustment/$name/"
            val source = resource(base + "source.docx")
            val reference = resource(base + "word-reference.pdf")
            val provenance = resource(base + "word-reference.pdf.provenance.json").decodeToString()
            assertTrue(provenance.contains(hash(source)) && provenance.contains(hash(reference)))
            val clean = ByteArrayOutputStream()
            val report = provider.cleanTemplate(source.inputStream(), clean, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
            assertTrue(report.changes.isEmpty(), report.changes.toString())
            assertArrayEquals(source, clean.toByteArray())
            val goldenBase = "/automatic-text-spacing/$name/"
            val golden = resource(goldenBase + "page-1.png")
            val goldenProvenance = resource(goldenBase + "golden-provenance.json").decodeToString()
            assertTrue(listOf(resource(goldenBase + "source.docx"), resource(goldenBase + "word-reference.pdf"), golden).all { goldenProvenance.contains(hash(it)) })
            val expected = glyphs(reference)
            val withoutSwitches = PdfFixture.zip(PdfFixture.unzip(source).mapValues { (part, bytes) ->
                if (part.endsWith(".xml")) bytes.decodeToString().replace(Regex("<w:adjustRightInd(?:\\s[^>]*)?/>", RegexOption.DOT_MATCHES_ALL), "").toByteArray() else bytes
            })
            Loader.loadPDF(render(provider, withoutSwitches, "standard")).use { baseline ->
                val unchanged = PDFRenderer(baseline).renderImageWithDPI(0, 96f)
                for (profile in listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b")) {
                    val bytes = render(provider, source, profile)
                    Files.write(directory.resolve("$name-$profile.pdf"), bytes)
                    val actual = glyphs(bytes)
                    assertEquals(expected.map { it.first }, actual.map { it.first })
                    for (index in expected.indices) {
                        assertEquals(expected[index].second, actual[index].second, .75f, "$name $profile glyph $index x")
                        assertEquals(expected[index].third, actual[index].third, .4f, "$name $profile glyph $index baseline")
                    }
                    Loader.loadPDF(bytes).use { pdf ->
                        assertEquals(1, pdf.numberOfPages)
                        assertEquals(if (name == "letter") 612f else 595.3f, pdf.getPage(0).mediaBox.width, .1f)
                        assertTrue(pdf.getPage(0).resources.fontNames.all { pdf.getPage(0).resources.getFont(it).isEmbedded })
                        val raster = PDFRenderer(pdf).renderImageWithDPI(0, 96f)
                        assertImagesMatch(unchanged, raster) {
                            pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                        }
                        assertImagesMatch(ImageIO.read(golden.inputStream()), raster) {
                            pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                        }
                        ImageIO.write(raster, "png", directory.resolve("$name-$profile.png").toFile())
                    }
                }
            }
        }
    }

    @Test
    fun `active document grid fails explicitly without publishing a partial PDF`() {
        val source = resource("/right-indent-adjustment/a4/source.docx")
        val changed = PdfFixture.zip(PdfFixture.unzip(source).mapValues { (part, bytes) ->
            if (part == "word/document.xml") bytes.decodeToString().replace("<w:sectPr>", "<w:sectPr><w:docGrid w:type=\"linesAndChars\" w:linePitch=\"360\"/>").toByteArray() else bytes
        })
        val output = ByteArrayOutputStream()
        val error = assertThrows(GenerationException::class.java) {
            provider().generate(GenerationRequest(changed.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                FormatRequest(DocumentFormat.PDF, mapOf("profile" to "standard")))), output, GenerationContext())
        }
        assertEquals(0, output.size())
        assertTrue(error.diagnostics.any { it.rejectionReason == RejectionReason.NATIVE_DOCX_PROFILE_UNSUPPORTED })
    }

}
