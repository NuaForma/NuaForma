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

class NativeMixedNaturalLinesTest {
    @TempDir lateinit var temporary: Path

    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes() }

    private fun provider() = DocxPdfProvider(NativePdfConfiguration(listOf(
        NativePdfFont("Liberation Sans", "regular", resource("/own-engine-fonts/LiberationSans-Regular.ttf"))),
        PdfProcessValidator(Path.of(System.getProperty("nuaforma.pdf.validation.java")),
            Files.list(Path.of(System.getProperty("nuaforma.pdf.validation"))).use { files -> files.filter { it.toString().endsWith(".jar") }.sorted().toList() }, temporary)))

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun glyphs(bytes: ByteArray): List<List<Triple<String, Float, Float>>> = Loader.loadPDF(bytes).use { pdf ->
        (1..pdf.numberOfPages).map { page ->
            val result = mutableListOf<Triple<String, Float, Float>>()
            object : PDFTextStripper() {

                override fun processTextPosition(position: TextPosition) {
                    if (position.unicode.isNotBlank()) result += Triple(position.unicode, position.xDirAdj, position.yDirAdj)
                    super.processTextPosition(position)
                }

            }.also { it.startPage = page; it.endPage = page }.getText(pdf)
            result
        }
    }

    @Test
    fun `mixed physical line sizes match Word on A4 and Letter in all output profiles`() {
        val provider = provider()
        val directory = Files.createDirectories(Path.of("build/241-mixed-natural"))
        for (name in listOf("a4", "letter")) {
            val base = "/mixed-natural-lines/$name/"
            val source = resource(base + "source.docx")
            val reference = resource(base + "word-reference.pdf")
            val provenance = resource(base + "word-reference.pdf.provenance.json").decodeToString()
            assertTrue(provenance.contains(hash(source)) && provenance.contains(hash(reference)))
            val goldenProvenance = resource(base + "golden-provenance.json").decodeToString()
            assertTrue(goldenProvenance.contains(hash(source)) && goldenProvenance.contains(hash(reference)))
            val clean = ByteArrayOutputStream()
            assertTrue(provider.cleanTemplate(source.inputStream(), clean,
                GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))).changes.isEmpty())
            assertArrayEquals(source, clean.toByteArray())
            val expected = glyphs(reference)
            assertEquals(2, expected.size)
            for (profile in listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b")) {
                val output = ByteArrayOutputStream()
                provider.generate(GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                    FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile)))), output, GenerationContext())
                val bytes = output.toByteArray()
                Files.write(directory.resolve("$name-$profile.pdf"), bytes)
                val actual = glyphs(bytes)
                assertEquals(expected.size, actual.size)
                for (page in expected.indices) {
                    assertEquals(expected[page].map { it.first }, actual[page].map { it.first }, "$name $profile page $page content")
                    for (index in expected[page].indices) {
                        assertEquals(expected[page][index].second, actual[page][index].second, .75f, "$name $profile page $page glyph $index x")
                        assertEquals(expected[page][index].third, actual[page][index].third, .4f, "$name $profile page $page glyph $index baseline")
                    }
                }
                Loader.loadPDF(bytes).use { pdf ->
                    for (page in 0 until pdf.numberOfPages) {
                        assertEquals(if (name == "letter") 612f else 595.3f, pdf.getPage(page).mediaBox.width, .1f)
                        assertTrue(pdf.getPage(page).resources.fontNames.all { pdf.getPage(page).resources.getFont(it).isEmbedded })
                        val golden = resource(base + "page-${page + 1}.png")
                        assertTrue(goldenProvenance.contains(hash(golden)))
                        val raster = PDFRenderer(pdf).renderImageWithDPI(page, 96f)
                        assertImagesMatch(ImageIO.read(golden.inputStream()), raster) {
                            pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                        }
                        ImageIO.write(raster, "png", directory.resolve("$name-$profile-page-${page + 1}.png").toFile())
                    }
                }
            }
        }
    }

}
