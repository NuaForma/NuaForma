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

class NativeJustificationMetadataTest {
    @TempDir lateinit var temporary: Path
    private val word = PdfFixture.W
    private val office = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private val relations = "http://schemas.openxmlformats.org/package/2006/relationships"

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
    fun `justification preserves Word line endpoints final lines and signed paragraph indents in every profile`() {
        val source = resource("/justification/source.docx")
        val reference = resource("/justification/word-reference.pdf")
        val golden = resource("/justification/page-1.png")
        val provenance = resource("/justification/word-reference.pdf.provenance.json").decodeToString()
        val goldenProvenance = resource("/justification/golden-provenance.json").decodeToString()
        assertTrue(provenance.contains(hash(source)) && provenance.contains(hash(reference)))
        assertTrue(listOf(source, reference, golden).all { goldenProvenance.contains(hash(it)) })
        val expected = glyphs(reference)
        val provider = provider()
        val directory = Files.createDirectories(Path.of("build/235-justification"))
        for (profile in listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b")) {
            val bytes = render(provider, source, profile)
            Files.write(directory.resolve("$profile.pdf"), bytes)
            val actual = glyphs(bytes)
            assertEquals(expected.map { it.first }, actual.map { it.first })
            for (index in expected.indices) {
                assertEquals(expected[index].second, actual[index].second, .75f, "$profile glyph $index x")
                assertEquals(expected[index].third, actual[index].third, .4f, "$profile glyph $index baseline")
            }
            Loader.loadPDF(bytes).use { pdf ->
                assertEquals(1, pdf.numberOfPages)
                assertTrue(pdf.getPage(0).resources.fontNames.all { pdf.getPage(0).resources.getFont(it).isEmbedded })
                val raster = PDFRenderer(pdf).renderImageWithDPI(0, 96f)
                assertImagesMatch(ImageIO.read(golden.inputStream()), raster) {
                    pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                }
                ImageIO.write(raster, "png", directory.resolve("$profile.png").toFile())
            }
        }
    }

    @Test
    fun `font-save settings never authorize a missing visible font`() {
        val parts = PdfFixture.unzip(metadata(resource("/justification/source.docx"))).mapValues { (name, bytes) ->
            if (name == "word/styles.xml") bytes.decodeToString().replace("Liberation Sans", "Unavailable Family").toByteArray() else bytes
        }
        val output = ByteArrayOutputStream()
        val error = assertThrows(GenerationException::class.java) {
            provider().generate(GenerationRequest(PdfFixture.zip(parts).inputStream(), GenerationRequirements(
                FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.PDF, mapOf("profile" to "standard")))), output, GenerationContext())
        }
        assertTrue(error.diagnostics.any { it.rejectionReason == RejectionReason.USED_FONT_FACE_UNAVAILABLE })
        assertEquals(0, output.size())
    }

    private fun metadata(source: ByteArray): ByteArray {
        val parts = PdfFixture.unzip(source).toMutableMap()

        fun append(part: String, end: String, value: String) {
            parts[part] = parts.getValue(part).decodeToString().replace(end, value + end).toByteArray()
        }

        parts["word/settings.xml"] = """<w:settings xmlns:w="$word"><w:embedTrueTypeFonts/><w:saveSubsetFonts w:val="true"/></w:settings>""".toByteArray()
        parts["word/numbering.xml"] = """<w:numbering xmlns:w="$word"><w:numIdMacAtCleanup w:val="42"/></w:numbering>""".toByteArray()
        for (name in listOf("settings", "numbering")) {
            append("word/_rels/document.xml.rels", "</Relationships>", """<Relationship Id="$name" Type="$office/$name" Target="$name.xml"/>""")
            append("[Content_Types].xml", "</Types>", """<Override PartName="/word/$name.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.$name+xml"/>""")
        }
        val png = ByteArrayOutputStream().also { ImageIO.write(java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        parts["docProps/thumbnail.png"] = png
        append("_rels/.rels", "</Relationships>", """<Relationship Id="preview" Type="$relations/metadata/thumbnail" Target="docProps/thumbnail.png"/>""")
        append("[Content_Types].xml", "</Types>", """<Default Extension="png" ContentType="image/png"/>""")
        return PdfFixture.zip(parts)
    }

    @Test
    fun `passive numbering preview and font-save metadata leave all PDF pixels unchanged and template intact`() {
        val source = resource("/justification/source.docx")
        val decorated = metadata(source)
        val provider = provider()
        for (profile in listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b")) {
            Loader.loadPDF(render(provider, source, profile)).use { expected ->
                Loader.loadPDF(render(provider, decorated, profile)).use { actual ->
                    assertEquals(PDFTextStripper().getText(expected), PDFTextStripper().getText(actual))
                    assertImagesMatch(PDFRenderer(expected).renderImageWithDPI(0, 96f), PDFRenderer(actual).renderImageWithDPI(0, 96f)) {
                        pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                    }
                }
            }
        }
        val clean = ByteArrayOutputStream()
        val report = provider.cleanTemplate(decorated.inputStream(), clean, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
        assertTrue(report.changes.isEmpty(), report.changes.toString())
        assertArrayEquals(decorated, clean.toByteArray())
    }

}
