package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import dev.nuaforma.pdf.PdfProcessValidator
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.apache.pdfbox.pdmodel.PDDocument
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO

class NativeTypographyPreferencesTest {
    @TempDir lateinit var temporary: Path

    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes() }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun characters(document: PDDocument): List<Triple<String, Float, Float>> {
        val result = mutableListOf<Triple<String, Float, Float>>()
        object : PDFTextStripper() {

            override fun processTextPosition(position: TextPosition) {
                if (position.unicode.isNotBlank()) result += Triple(position.unicode, position.xDirAdj, position.yDirAdj)
                super.processTextPosition(position)
            }

        }.getText(document)
        return result.sortedWith(compareBy({ it.third }, { it.second }))
    }

    @Test
    fun `independent Word typography cases survive cleanup and validated PDF profiles with frozen raster output`() {
        val fonts = listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic", "boldItalic" to "BoldItalic").map { (face, file) ->
            NativePdfFont("Liberation Sans", face, resource("/own-engine-fonts/LiberationSans-$file.ttf"))
        } + NativePdfFont("Noto Serif", "regular", resource("/own-engine-fonts/NotoSerif-Regular.ttf"))
        val validator = PdfProcessValidator(Path.of(System.getProperty("nuaforma.pdf.validation.java")),
            Files.list(Path.of(System.getProperty("nuaforma.pdf.validation"))).use { paths ->
                paths.filter { it.toString().endsWith(".jar") }.sorted().toList()
            }, temporary)
        val provider = DocxPdfProvider(NativePdfConfiguration(fonts, validator))
        val directory = Files.createDirectories(Path.of("build/232-typography-preferences"))
        for (name in listOf("trailing-0", "trailing-1", "editor", "ligatures", "hyphenation", "bands-letter", "inherited-underline", "strike-0", "strike-1")) {
            val base = "/typography-preferences/$name/"
            val source = resource(base + "source.docx")
            val reference = resource(base + "word-reference.pdf")
            val provenance = resource(base + "word-reference.pdf.provenance.json").decodeToString()
            assertTrue(provenance.contains("\"sourceSha256\": \"${hash(source)}\""), name)
            assertTrue(provenance.contains("\"pdfSha256\": \"${hash(reference)}\""), name)
            val clean = ByteArrayOutputStream()
            assertTrue(provider.cleanTemplate(source.inputStream(), clean,
                GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))).changes.isEmpty(), name)
            assertArrayEquals(source, clean.toByteArray(), name)
            Loader.loadPDF(reference).use { word ->
                val expected = characters(word)
                for (profile in listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b")) {
                    val output = ByteArrayOutputStream()
                    try {
                        provider.generate(GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                            FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile)))), output,
                            GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
                    } catch (failure: GenerationException) {
                        throw AssertionError("$name $profile: ${failure.diagnostics}", failure)
                    }
                    val bytes = output.toByteArray()
                    Files.write(directory.resolve("$name-$profile.pdf"), bytes)
                    Loader.loadPDF(bytes).use { pdf ->
                        assertEquals(word.numberOfPages, pdf.numberOfPages, name)
                        val actual = characters(pdf)
                        assertEquals(expected.map { it.first }, actual.map { it.first }, name)
                        for (index in expected.indices) {
                            assertEquals(expected[index].second, actual[index].second, .5f, "$name glyph $index x")
                            assertEquals(expected[index].third, actual[index].third, .25f, "$name glyph $index baseline")
                        }
                        assertTrue(pdf.pages.all { page -> page.resources.fontNames.all { page.resources.getFont(it).isEmbedded } })
                        assertImagesMatch(ImageIO.read(resource(base + "page-1.png").inputStream()),
                            PDFRenderer(pdf).renderImageWithDPI(0, 96f)) {
                            pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                        }
                    }
                }
            }
        }
    }

}
