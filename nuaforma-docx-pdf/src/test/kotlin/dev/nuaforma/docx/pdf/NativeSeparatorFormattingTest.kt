package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO

class NativeSeparatorFormattingTest {
    @TempDir lateinit var temporary: Path

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `nonprinting separator metadata preserves both pages and note ownership`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        for (kind in listOf("control", "empty-properties", "size24", "size36", "leading-reference", "styled-leading-reference")) {
            val profiles = if (paper == "a4" && kind in setOf("size24", "styled-leading-reference"))
                listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard")
            support.verify("/note-separator-formatting/$kind/$paper/", profiles, Regex("(?:CALL|NOTE)[0-9]{2}"), 24,
                pageCount = 2, goldenDirectory = "/note-separator-formatting/control/$paper/")
        }
    }

    @Test
    fun `cleanup retains original note separators and reuses the template unchanged`() {
        val support = NativeWordFeatureSupport(temporary)
        val context = GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))
        for (kind in listOf("size24", "styled-leading-reference")) {
            val source = support.bytes("/note-separator-formatting/$kind/a4/source.docx")
            val copy = source.copyOf()
            val first = ByteArrayOutputStream()
            assertTrue(support.provider.cleanTemplate(source.inputStream(), first, context).changes.isEmpty())
            assertArrayEquals(source, first.toByteArray())
            val second = ByteArrayOutputStream()
            assertTrue(support.provider.cleanTemplate(first.toByteArray().inputStream(), second, context).changes.isEmpty())
            assertArrayEquals(source, second.toByteArray())
            assertTrue(support.generate(second.toByteArray(), "standard").isNotEmpty())
            assertArrayEquals(copy, source)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["continuation:a4", "continuation:letter", "endnotes:a4", "endnotes:letter"])
    fun `continued and endnote separators retain their existing native page ownership`(scenario: String) {
        val story = scenario.substringBefore(':')
        val paper = scenario.substringAfter(':')
        val support = NativeWordFeatureSupport(temporary)
        val control = "/note-separator-formatting/$story/control/$paper/"
        val combined = "/note-separator-formatting/$story/combined/$paper/"
        Loader.loadPDF(support.bytes(control + "word-reference.pdf")).use { wordControl ->
            Loader.loadPDF(support.bytes(combined + "word-reference.pdf")).use { wordCombined ->
                assertEquals(2, wordControl.numberOfPages)
                assertEquals(2, wordCombined.numberOfPages)
                for (page in 0..1) assertImagesMatch(PDFRenderer(wordControl).renderImageWithDPI(page, 144f),
                    PDFRenderer(wordCombined).renderImageWithDPI(page, 144f)) {
                    pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                }
            }
        }
        for (folder in listOf(control, combined)) {
            val source = support.bytes(folder + "source.docx")
            val proof = support.bytes(folder + "golden-provenance.json").decodeToString()
            for (bytes in listOf(source, support.bytes(folder + "word-reference.pdf"),
                support.bytes(control + "page-1.png"), support.bytes(control + "page-2.png"))) assertTrue(proof.contains(hash(bytes)))
            assertTrue(support.bytes(folder + "word-reference.pdf.provenance.json").decodeToString().contains(hash(source)))
            val profiles = if (paper == "a4" && folder == combined) listOf("standard", "pdfa-2b") else listOf("standard")
            for (profile in profiles) Loader.loadPDF(support.generate(source, profile)).use { actual ->
                assertEquals(2, actual.numberOfPages)
                val text = PDFTextStripper().getText(actual)
                if (story == "continuation") {
                    for (id in 1..38) assertEquals(1, Regex("N2\\.${id.toString().padStart(2, '0')}(?![0-9])").findAll(text).count())
                    for (marker in listOf("FN001", "FN002", "END")) assertEquals(1, Regex(marker).findAll(text).count())
                } else for (prefix in listOf("CALL", "NOTE")) for (id in 1..12)
                    assertEquals(1, Regex(prefix + id.toString().padStart(2, '0') + "(?![0-9])").findAll(text).count())
                for (page in 0..1) {
                    assertTrue(actual.getPage(page).resources.fontNames.all { actual.getPage(page).resources.getFont(it).isEmbedded })
                    assertImagesMatch(ImageIO.read(support.bytes(control + "page-${page + 1}.png").inputStream()),
                        PDFRenderer(actual).renderImageWithDPI(page, 96f)) {
                        pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                    }
                }
            }
        }
    }

    @Test
    fun `effective malformed foreign or reordered separator constructs remain precise failures`() {
        val support = NativeWordFeatureSupport(temporary)
        val source = "/note-separator-formatting/size24/a4/source.docx"
        for (replacement in listOf("<w:sz w:val=\"25\" />", "<w:sz val=\"24\" />",
            "<w:sz w:val=\"24\" unexpected=\"1\" />", "<f:sz xmlns:f=\"urn:future\" w:val=\"24\" />",
            "<w:color w:val=\"FF0000\" />", "<w:sz w:val=\"24\" /><w:sz w:val=\"24\" />")) {
            support.rejectChanged(source, "word/footnotes.xml", "rPr") { it.replace("<w:sz w:val=\"24\" />", replacement) }
        }
        support.rejectChanged(source, "word/footnotes.xml", "rPr") {
            it.replace("<w:sz w:val=\"24\" />", "<w:rStyle w:val=\"Reference\" />")
        }
        support.rejectChanged("/note-separator-formatting/styled-leading-reference/a4/source.docx", "word/footnotes.xml", "footnoteRef") {
            it.replace("<w:footnoteRef />", "<w:footnoteRef unexpected=\"1\" />")
        }
    }
}
