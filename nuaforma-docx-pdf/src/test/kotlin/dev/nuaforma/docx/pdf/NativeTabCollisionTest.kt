package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO

class NativeTabCollisionTest {
    @TempDir lateinit var temporary: Path

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun content(pdf: PDDocument): String {
        val text = PDFTextStripper().apply { sortByPosition = true }.getText(pdf).replace(Regex("[\\p{Z}\\s]+"), "")
        return text.replace(Regex("(P[0-9]{2}W*)[._-]+(?=123456,T[0-9]{2})"), "$1")
    }

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `data expansion preserves Word collision and passed-stop behavior`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        val pattern = Regex("[PT][0-9]{2}|H0[01]|12345678901234,H02")
        val source = support.bytes("/tab-collisions/template/$paper/source.docx")
        for ((variant, value) in listOf("short" to "", "collision" to "WWWWW", "past" to "WWWWWWWW")) {
            val folder = "/tab-collisions/$variant/$paper/"
            val profiles = if (paper == "a4" && variant == "collision")
                listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard")
            val reference = support.bytes(folder + "word-reference.pdf")
            val image = support.bytes(folder + "page-1.png")
            val proof = support.bytes(folder + "golden-provenance.json").decodeToString()
            for (bytes in listOf(source, reference, image, support.bytes(folder + "source.docx"))) assertTrue(proof.contains(hash(bytes)))
            val wordProof = support.bytes(folder + "word-reference.pdf.provenance.json").decodeToString()
            assertTrue(wordProof.contains(hash(support.bytes(folder + "source.docx"))))
            assertTrue(wordProof.contains(hash(reference)))
            Loader.loadPDF(reference).use { word ->
                val expected = support.positions(word, pattern)
                assertEquals(43, expected.size)
                assertEquals(1, word.numberOfPages)
                for (profile in profiles) {
                    val output = ByteArrayOutputStream()
                    support.provider.generate(GenerationRequest(source.inputStream(), GenerationRequirements(
                        FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile))),
                        mapOf("prefix" to value)), output, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
                    Loader.loadPDF(output.toByteArray()).use { actual ->
                        if (profile == "standard") {
                            val target = Files.createDirectories(Path.of("build/tab-collision-review/$variant/$paper"))
                            Files.write(target.resolve("native.pdf"), output.toByteArray())
                            ImageIO.write(PDFRenderer(actual).renderImageWithDPI(0, 96f), "png", target.resolve("page-1.png").toFile())
                        }
                        assertEquals(1, actual.numberOfPages)
                        val found = support.positions(actual, pattern)
                        assertEquals(expected.keys, found.keys)
                        for ((marker, before) in expected) {
                            val after = found.getValue(marker)
                            assertEquals(before.x, after.x, .21f, "$paper/$variant/$profile/$marker X")
                            assertEquals(before.y, after.y, .21f, "$paper/$variant/$profile/$marker Y")
                        }
                        val text = PDFTextStripper().getText(actual)
                        assertFalse(text.contains("{prefix}"))
                        assertEquals(content(word), content(actual))
                        assertTrue(actual.getPage(0).resources.fontNames.all { actual.getPage(0).resources.getFont(it).isEmbedded })
                        assertImagesMatch(ImageIO.read(image.inputStream()), PDFRenderer(actual).renderImageWithDPI(0, 96f)) {
                            pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                        }
                    }
                }
            }
        }
    }

}
