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
import javax.imageio.ImageIO

class NativeReviewAnnotationsTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `text comments modern sidecars and stale empty comment caches retain the exact clean Word control`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        for (variant in listOf("classic", "modern", "stale-modern", "reference-style")) {
            support.verify("/review-annotations/$variant/$paper/",
                if (variant == "modern" && paper == "a4") listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard"),
                Regex("(?!)"), 0, golden = "/preferred-table-widths/$paper/page-1.png")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `visible expressions span comment anchors and annotation text never becomes template instructions`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        val source = support.bytes("/review-annotations/templated/$paper/source.docx")
        val before = source.copyOf()
        val output = ByteArrayOutputStream()
        val request = GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
            FormatRequest(DocumentFormat.PDF, mapOf("profile" to "standard"))), mapOf("label" to "PREFERRED TABLE WIDTHS ${paper.uppercase()}"))
        support.provider.generate(request, output, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
        Loader.loadPDF(output.toByteArray()).use { actual ->
            Loader.loadPDF(support.bytes("/preferred-table-widths/$paper/word-reference.pdf")).use { word ->
                assertEquals(1, actual.numberOfPages)
                assertEquals(PDFTextStripper().apply { sortByPosition = true }.getText(word).replace(Regex("\\s+"), ""),
                    PDFTextStripper().apply { sortByPosition = true }.getText(actual).replace(Regex("\\s+"), ""))
                assertImagesMatch(ImageIO.read(support.bytes("/preferred-table-widths/$paper/page-1.png").inputStream()),
                    PDFRenderer(actual).renderImageWithDPI(0, 96f)) {
                    pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                }
            }
            assertTrue(actual.getPage(0).resources.fontNames.all { actual.getPage(0).resources.getFont(it).isEmbedded })
        }
        assertArrayEquals(before, source)
    }

    @Test
    fun `missing duplicate external or active review constructs retain source diagnostics and publish no PDF`() {
        val support = NativeWordFeatureSupport(temporary)
        val source = "/review-annotations/modern/a4/source.docx"
        support.rejectChanged(source, "word/document.xml", "commentRangeStart") {
            it.replaceFirst("<w:commentRangeStart w:id=\"0\"/>", "<w:commentRangeStart w:id=\"999\"/>")
        }
        support.rejectChanged(source, "word/comments.xml", "comment") {
            it.replaceFirst("w:id=\"1\"", "w:id=\"0\"")
        }
        support.rejectChanged(source, "word/comments.xml", "fldSimple") {
            it.replaceFirst("<w:p w14:paraId=\"10000001\">", "<w:p w14:paraId=\"10000001\"><w:fldSimple w:instr=\"INCLUDETEXT ignored\"/>")
        }
        support.rejectChanged(source, "word/_rels/document.xml.rels", "Relationship") {
            it.replaceFirst("Target=\"comments.xml\"", "Target=\"https://example.invalid/comments.xml\" TargetMode=\"External\"")
        }
        support.rejectChanged(source, "word/commentsIds.xml", "commentId") {
            it.replaceFirst("w16cid:durableId=\"20000002\"", "w16cid:durableId=\"20000001\"")
        }
    }

    @Test
    fun `supported annotations survive template cleanup and ordinary DOCX generation`() {
        val support = NativeWordFeatureSupport(temporary)
        val source = support.bytes("/review-annotations/stale-modern/a4/source.docx")
        val cleaned = ByteArrayOutputStream()
        val result = support.provider.cleanTemplate(source.inputStream(), cleaned, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
        assertTrue(result.changes.isEmpty())
        assertArrayEquals(source, cleaned.toByteArray())
        val generated = ByteArrayOutputStream()
        support.provider.generate(GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
            FormatRequest(DocumentFormat.DOCX))), generated, GenerationContext())
        val originalParts = PdfFixture.unzip(source)
        val actualParts = PdfFixture.unzip(generated.toByteArray())
        for (part in listOf("word/comments.xml", "word/commentsExtended.xml", "word/commentsIds.xml", "word/commentsExtensible.xml", "word/people.xml")) {
            assertTrue(part in originalParts)
            assertTrue(part in actualParts, part)
        }
    }

}
