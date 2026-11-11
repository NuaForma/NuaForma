package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.nio.file.Path

class NativeNumericCleanupTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `cleanup returns canonical reusable templates with the same PDF pixels`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        val paths = listOf("fractional-numeric/grid/raw", "fractional-numeric/padding/raw",
            "fractional-numeric/automatic-line/raw", "fractional-table-indent/12-fraction",
            "fractional-table-indent/12-zero", "fractional-table-indent/15-style-zero")
        for (path in paths) {
            val original = support.bytes("/$path/$paper/source.docx")
            val before = original.copyOf()
            val cleaned = ByteArrayOutputStream()
            val report = support.provider.cleanTemplate(original.inputStream(), cleaned, development())
            assertTrue(report.changes.isNotEmpty(), path)
            assertTrue(report.changes.all { it.action == TemplateCleanupAction.RESTORE_REQUIRED_STRUCTURE }, path)
            val second = ByteArrayOutputStream()
            assertTrue(support.provider.cleanTemplate(cleaned.toByteArray().inputStream(), second, development()).changes.isEmpty(), path)
            assertArrayEquals(cleaned.toByteArray(), second.toByteArray(), path)
            Loader.loadPDF(support.generate(original, "standard")).use { expected ->
                Loader.loadPDF(support.generate(cleaned.toByteArray(), "standard")).use { actual ->
                    assertEquals(expected.numberOfPages, actual.numberOfPages, path)
                    for (page in 0 until actual.numberOfPages) assertImagesMatch(
                        PDFRenderer(expected).renderImageWithDPI(page, 96f), PDFRenderer(actual).renderImageWithDPI(page, 96f)) {
                        pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                    }
                }
            }
            assertArrayEquals(before, original, path)
        }
    }

    @Test
    fun `numeric cleanup preserves unbound template instructions and original comment parts`() {
        val support = NativeWordFeatureSupport(temporary)
        val source = PdfFixture.unzip(support.bytes("/review-annotations/templated/a4/source.docx")).toMutableMap()
        val document = source.getValue("word/document.xml").decodeToString()
        val fractional = document.replaceFirst("w:w=\"2000\"", "w:w=\"2000.8\"")
        assertNotEquals(document, fractional)
        source["word/document.xml"] = fractional.toByteArray()
        val cleaned = ByteArrayOutputStream()
        val report = support.provider.cleanTemplate(PdfFixture.zip(source).inputStream(), cleaned, development())
        assertTrue(report.changes.isNotEmpty())
        val actual = PdfFixture.unzip(cleaned.toByteArray())
        for ((part, bytes) in source) if (part != "word/document.xml") assertArrayEquals(bytes, actual[part], part)
        val xml = dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml(development())
        val expectedRoot = xml.readPart(document.toByteArray(), "word/document.xml")
        val actualRoot = xml.readPart(actual.getValue("word/document.xml"), "word/document.xml")
        assertEquals(expectedRoot.descendants().map { it.name to it.attributes }.toList(),
            actualRoot.descendants().map { it.name to it.attributes }.toList())

        fun text(root: dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement) = root.descendants()
            .flatMap { it.children.filterIsInstance<dev.nuaforma.docx.pdf.adapters.ooxml.XmlText>() }.map { it.text }.toList()

        assertEquals(text(expectedRoot), text(actualRoot))
    }

    private fun development() = GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))
}
