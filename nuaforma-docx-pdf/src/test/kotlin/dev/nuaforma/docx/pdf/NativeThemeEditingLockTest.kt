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

class NativeThemeEditingLockTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `theme editing locks preserve actual palette rendering and existing pixels`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        for (kind in listOf("control", "enabled", "disabled")) {
            val profiles = if (kind == "enabled" && paper == "a4")
                listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard")
            support.verify("/passive-print-metadata/theme-lock/$kind/$paper/", profiles, Regex("(?!)"), 0,
                pageCount = 2, goldenDirectory = "/theme-colors/$paper/")
        }
    }

    @Test
    fun `cleanup preserves supported editing locks without modifying the theme`() {
        val support = NativeWordFeatureSupport(temporary)
        val context = GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))
        for (kind in listOf("enabled", "disabled")) {
            val source = support.bytes("/passive-print-metadata/theme-lock/$kind/a4/source.docx")
            val output = ByteArrayOutputStream()
            assertTrue(support.provider.cleanTemplate(source.inputStream(), output, context).changes.isEmpty())
            assertArrayEquals(source, output.toByteArray())
            val original = PdfFixture.unzip(source)
            val cleaned = PdfFixture.unzip(output.toByteArray())
            val themes = original.filterKeys { it.startsWith("word/theme/") }
            assertTrue(themes.isNotEmpty())
            themes.forEach { (part, bytes) -> assertArrayEquals(bytes, cleaned[part], part) }
            val second = ByteArrayOutputStream()
            assertTrue(support.provider.cleanTemplate(output.toByteArray().inputStream(), second, context).changes.isEmpty())
            assertArrayEquals(output.toByteArray(), second.toByteArray())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `quick style editing locks preserve output and the original setting`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        val source = support.bytes("/passive-print-metadata/theme-lock/control/$paper/source.docx")
        val parts = PdfFixture.unzip(source)
        val part = "word/settings.xml"
        val settings = parts.getValue(part).decodeToString()
        val reference = support.generate(source, "standard")
        for (value in listOf("", " w:val=\"0\"")) {
            val node = "<w:styleLockQFSet$value/>"
            val changed = PdfFixture.zip(parts + (part to settings.replace("</w:settings>", "$node</w:settings>").toByteArray()))
            val clean = ByteArrayOutputStream()
            val context = GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))
            assertTrue(support.provider.cleanTemplate(changed.inputStream(), clean, context).changes.isEmpty())
            assertArrayEquals(changed, clean.toByteArray())
            Loader.loadPDF(reference).use { expected ->
                Loader.loadPDF(support.generate(changed, "standard")).use { actual ->
                    assertEquals(expected.numberOfPages, actual.numberOfPages)
                    val expectedRenderer = PDFRenderer(expected)
                    val actualRenderer = PDFRenderer(actual)
                    for (page in 0 until expected.numberOfPages) {
                        assertImagesMatch(expectedRenderer.renderImageWithDPI(page, 96f), actualRenderer.renderImageWithDPI(page, 96f)) {
                            pixelTolerance = 0.0
                            allowedDifferentPixelsPercent = 0.0
                            threshold = 1
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `invalid editing locks fail at the original settings element`() {

        val support = NativeWordFeatureSupport(temporary)
        val source = "/passive-print-metadata/theme-lock/enabled/a4/source.docx"
        val condition = "<w:styleLockTheme w:val=\"1\"/>"
        for (name in listOf("styleLockTheme", "styleLockQFSet")) {
            val node = "<w:$name w:val=\"1\"/>"
            for (replacement in listOf("<w:$name w:val=\"maybe\"/>", "$node$node",
                "<w:$name w:val=\"1\" unexpected=\"1\"/>",
                "<w:$name><w:t>{hidden</w:t></w:$name>",
                "<f:$name xmlns:f=\"urn:foreign\" w:val=\"1\"/>")) {
                support.rejectChanged(source, "word/settings.xml", name) { it.replace(condition, replacement) }
            }
        }
    }
}
