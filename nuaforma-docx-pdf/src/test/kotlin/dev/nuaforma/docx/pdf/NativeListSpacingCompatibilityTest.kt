package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.adapters.nativeengine.NativeDocxReader
import dev.nuaforma.docx.pdf.adapters.nativeengine.NativeDocxReadException
import dev.nuaforma.pdf.*
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.pdmodel.PDDocument
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

class NativeListSpacingCompatibilityTest {
    @TempDir lateinit var temporary: Path

    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes() }

    private fun source(name: String) = resource("/list-spacing-compatibility/$name/source.docx")

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun model(input: ByteArray) = NativeDocxReader(GenerationContext()) { _, _, _ -> "regular" }.read(input)

    private fun provider(): DocxPdfProvider {
        val font = NativePdfFont("Liberation Sans", "regular", resource("/own-engine-fonts/LiberationSans-Regular.ttf"))
        val validator = PdfProcessValidator(Path.of(System.getProperty("nuaforma.pdf.validation.java")),
            Files.list(Path.of(System.getProperty("nuaforma.pdf.validation"))).use { paths ->
                paths.filter { it.toString().endsWith(".jar") }.sorted().toList()
            }, temporary)
        return DocxPdfProvider(NativePdfConfiguration(listOf(font), validator))
    }

    private fun pdf(provider: DocxPdfProvider, input: ByteArray, profile: String) = ByteArrayOutputStream().also { output ->
        try {
            provider.generate(GenerationRequest(input.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile)))), output,
                GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
        } catch (error: GenerationException) {
            throw AssertionError("$profile: ${error.diagnostics}", error)
        }
    }.toByteArray()

    private fun characters(document: PDDocument): List<Triple<String, Float, Float>> {
        val result = mutableListOf<Triple<String, Float, Float>>()
        object : PDFTextStripper() {

            override fun processTextPosition(position: TextPosition) {
                if (position.unicode.isNotBlank()) result += Triple(position.unicode, position.xDirAdj, position.yDirAdj)
                super.processTextPosition(position)
            }

        }.getText(document)
        return result
    }

    @Test
    fun `full providers match independent Word text and baselines with validated embedding in all four profiles`() {
        val provider = provider()
        val directory = Files.createDirectories(Path.of("build/231-list-spacing-compatibility"))
        for (name in listOf("minimum-lines", "deep-one", "deep-starts", "full-override", "deep-bullet", "minimum-images")) {
            val input = source(name)
            val cleaned = ByteArrayOutputStream()
            assertTrue(provider.cleanTemplate(input.inputStream(), cleaned,
                GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))).changes.isEmpty(), name)
            assertArrayEquals(input, cleaned.toByteArray(), name)
            val reference = resource("/list-spacing-compatibility/$name/word-reference.pdf")
            val receipt = resource("/list-spacing-compatibility/$name/word-reference.pdf.provenance.json").decodeToString()
            assertTrue(receipt.contains("\"sourceSha256\": \"${hash(input)}\""), "$name Word source identity")
            assertTrue(receipt.contains("\"pdfSha256\": \"${hash(reference)}\""), "$name Word reference identity")
            Loader.loadPDF(reference).use { word ->
                val expected = characters(word)
                assertTrue(expected.isNotEmpty())
                for (profile in listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b")) {
                    val bytes = pdf(provider, input, profile)
                    Files.write(directory.resolve("$name-$profile.pdf"), bytes)
                    Loader.loadPDF(bytes).use { document ->
                        assertEquals(1, document.numberOfPages)
                        assertEquals(PDFTextStripper().getText(word).replace(Regex("\\s+"), " ").trim(),
                            PDFTextStripper().getText(document).replace(Regex("\\s+"), " ").trim(), name)
                        val actual = characters(document)
                        assertEquals(expected.map { it.first }, actual.map { it.first }, name)
                        for (index in expected.indices) {
                            assertEquals(expected[index].third, actual[index].third, .2f, "$name character $index baseline")
                            assertEquals(expected[index].second, actual[index].second, .5f, "$name character $index x")
                        }
                        assertTrue(document.pages.all { page -> page.resources.fontNames.all { page.resources.getFont(it).isEmbedded } })
                        val raster = PDFRenderer(document).renderImageWithDPI(0, 96f)
                        assertImagesMatch(ImageIO.read(resource("/list-spacing-compatibility/$name/page-1.png").inputStream()), raster) {
                            pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `existing precise style failures are not relabelled as numbering failures`() {
        val parts = PdfFixture.unzip(source("deep-one")).toMutableMap()
        parts["word/styles.xml"] = parts.getValue("word/styles.xml").decodeToString()
            .replaceFirst("</w:style>", "<w:unsupported/></w:style>").toByteArray()
        val failure = assertThrows(NativeDocxReadException::class.java) { model(PdfFixture.zip(parts)) }
        assertEquals("word/styles.xml", failure.part)
        assertEquals("Unsupported document feature", failure.message)
    }

    @Test
    fun `minimum spacing cascades while exact and automatic overrides clear the inherited floor`() {
        val parts = PdfFixture.unzip(source("minimum-lines")).toMutableMap()
        val word = PdfFixture.W
        parts["word/styles.xml"] = """<w:styles xmlns:w="$word"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:sz w:val="20"/></w:rPr></w:rPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:pPr><w:spacing w:line="280" w:lineRule="atLeast"/></w:pPr></w:style></w:styles>""".toByteArray()
        val input = parts.getValue("word/document.xml").decodeToString()
        parts["word/document.xml"] = input.replace("<w:body>", """<w:body><w:p><w:r><w:t>Inherited</w:t></w:r></w:p><w:p><w:pPr><w:spacing w:after="40"/></w:pPr><w:r><w:t>Gap only</w:t></w:r></w:p><w:p><w:pPr><w:spacing w:line="320" w:lineRule="exact"/></w:pPr><w:r><w:t>Exact</w:t></w:r></w:p><w:p><w:pPr><w:spacing w:line="240" w:lineRule="auto"/></w:pPr><w:r><w:t>Automatic</w:t></w:r></w:p>""").toByteArray()
        val paragraphs = model(PdfFixture.zip(parts)).blocks.filterIsInstance<PdfParagraph>()
        assertEquals(listOf(14f, 14f, null, null, 14f, 14f, 6f, 28f), paragraphs.map { it.minimumLineHeight })
        assertEquals(16f, paragraphs[2].format.exactLineHeight)
        assertEquals(1f, paragraphs[3].format.automaticLineSpacing!!.multiple)
        for (value in listOf("-1", "NaN", "288001")) {
            parts["word/document.xml"] = input.replace("w:line=\"280\"", "w:line=\"$value\"").toByteArray()
            assertThrows(IllegalArgumentException::class.java) { model(PdfFixture.zip(parts)) }
        }
    }

}
