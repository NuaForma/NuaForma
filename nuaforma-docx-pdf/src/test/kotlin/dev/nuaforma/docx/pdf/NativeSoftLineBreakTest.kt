package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO

class NativeSoftLineBreakTest {
    @TempDir lateinit var temporary: Path
    private val markers = Regex("L[0-9]{2}_[A-Z]+?(?=L[0-9]{2}_|[^A-Z]|$)")
    private val cases = linkedMapOf("ordinary" to 6, "alignment" to 8, "mixed" to 5, "carriage" to 3,
        "tabs" to 4, "mark-metrics" to 5, "table-bands" to 12, "pagination" to 6, "list" to 3)

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `soft lines retain Word geometry and protected pixels across body tables bands lists and pages`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        for ((kind, count) in cases) {
            val profiles = when {
                paper == "a4" && kind in setOf("ordinary", "table-bands") -> listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b")
                paper == "a4" && kind == "pagination" -> listOf("standard", "pdfa-3b")
                else -> listOf("standard")
            }
            support.verify("/soft-line-breaks/$kind/$paper/", profiles, markers, count, pageCount = if (kind == "pagination") 2 else 1)
        }
    }

    private fun sameImage(expected: BufferedImage, actual: BufferedImage) {
        assertImagesMatch(expected, actual) { pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1 }
    }

    private fun text(pdf: PDDocument): String = PDFTextStripper().apply { sortByPosition = true }.getText(pdf).replace(Regex("[\\p{Z}\\s]+"), "")

    private fun proof(support: NativeWordFeatureSupport, folder: String) {
        val receipt = support.bytes(folder + "golden-provenance.json").decodeToString()
        for (name in listOf("source.docx", "word-reference.pdf", "page-1.png")) {
            val hash = MessageDigest.getInstance("SHA-256").digest(support.bytes(folder + name)).joinToString("") { "%02x".format(it) }
            assertTrue(receipt.contains(hash), folder + name)
            if (name == "source.docx") assertTrue(support.bytes(folder + "word-reference.pdf.provenance.json").decodeToString().contains(hash))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `Hebrew soft lines match paragraph controls and retain Word text and baselines`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        val folder = "/soft-line-breaks/rtl/$paper/"
        val control = "/soft-line-breaks/paragraphs/$paper/"
        proof(support, folder)
        proof(support, control)
        Loader.loadPDF(support.bytes(folder + "word-reference.pdf")).use { word ->
            Loader.loadPDF(support.bytes(control + "word-reference.pdf")).use { reference ->
                assertEquals(text(word), text(reference))
                sameImage(PDFRenderer(word).renderImageWithDPI(0, 96f), PDFRenderer(reference).renderImageWithDPI(0, 96f))
            }
            val expected = support.positions(word, Regex("[א-ת]{3}"))
            assertEquals(3, expected.size)
            for (profile in if (paper == "a4") listOf("standard", "pdfa-3b") else listOf("standard")) {
                Loader.loadPDF(support.generate(support.bytes(folder + "source.docx"), profile)).use { actual ->
                    Loader.loadPDF(support.generate(support.bytes(control + "source.docx"), profile)).use { baseline ->
                        assertEquals(1, actual.numberOfPages)
                        assertEquals(1, baseline.numberOfPages)
                        assertEquals(text(word), text(actual))
                        assertEquals(text(actual), text(baseline))
                        val image = PDFRenderer(actual).renderImageWithDPI(0, 96f)
                        sameImage(image, PDFRenderer(baseline).renderImageWithDPI(0, 96f))
                        sameImage(ImageIO.read(support.bytes(folder + "page-1.png").inputStream()), image)
                        val found = support.positions(actual, Regex("[א-ת]{3}"))
                        assertEquals(expected.keys, found.keys)
                        for ((key, position) in expected) assertEquals(position.y, found.getValue(key).y, .21f, key)
                        assertEquals(word.getPage(0).mediaBox.width, actual.getPage(0).mediaBox.width, .1f)
                        assertTrue(actual.getPage(0).resources.fontNames.all { actual.getPage(0).resources.getFont(it).isEmbedded })
                    }
                }
            }
        }
    }

    @Test
    fun `cleanup preserves valid controls and explicit default spellings preserve pixels`() {
        val support = NativeWordFeatureSupport(temporary)
        val context = GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))
        for (kind in listOf("ordinary", "mixed", "table-bands", "list", "rtl")) {
            val source = support.bytes("/soft-line-breaks/$kind/a4/source.docx")
            val first = ByteArrayOutputStream()
            assertTrue(support.provider.cleanTemplate(source.inputStream(), first, context).changes.isEmpty())
            assertArrayEquals(source, first.toByteArray())
            val second = ByteArrayOutputStream()
            assertTrue(support.provider.cleanTemplate(first.toByteArray().inputStream(), second, context).changes.isEmpty())
            assertArrayEquals(source, second.toByteArray())
        }
        val parts = PdfFixture.unzip(support.bytes("/soft-line-breaks/ordinary/a4/source.docx")).toMutableMap()
        parts["word/document.xml"] = parts.getValue("word/document.xml").decodeToString()
            .replace("<w:br />", "<w:br w:type=\"textWrapping\" w:clear=\"none\"/>").toByteArray()
        Loader.loadPDF(support.generate(PdfFixture.zip(parts), "standard")).use { pdf ->
            sameImage(ImageIO.read(support.bytes("/soft-line-breaks/ordinary/a4/page-1.png").inputStream()), PDFRenderer(pdf).renderImageWithDPI(0, 96f))
        }
    }

    @Test
    fun `malformed original controls and active clear page or column policies reject before output`() {
        val support = NativeWordFeatureSupport(temporary)
        for (bad in listOf("<w:br w:type=\"invalid\"/>", "<w:br w:clear=\"invalid\"/>", "<w:br type=\"page\"/>",
            "<x:br xmlns:x=\"urn:future\"/>", "<w:br><w:t>hidden</w:t></w:br>", "<w:br>hidden</w:br>",
            "<w:br w:type=\"page\"/>", "<w:br w:type=\"column\"/>", "<w:br w:clear=\"all\"/>"))
            support.rejectChanged("/soft-line-breaks/ordinary/a4/source.docx", "word/document.xml", "br") { it.replace("<w:br />", bad) }
        support.rejectChanged("/soft-line-breaks/ordinary/a4/source.docx", "word/document.xml", "cr") {
            it.replace("<w:br />", "<w:cr w:type=\"textWrapping\"/>")
        }
    }
}
