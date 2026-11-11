package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
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

    @Test
    fun `invalid theme locks fail at the original settings element`() {
        val support = NativeWordFeatureSupport(temporary)
        val source = "/passive-print-metadata/theme-lock/enabled/a4/source.docx"
        val node = "<w:styleLockTheme w:val=\"1\"/>"
        for (replacement in listOf("<w:styleLockTheme w:val=\"maybe\"/>", "$node$node",
            "<w:styleLockTheme w:val=\"1\" unexpected=\"1\"/>",
            "<w:styleLockTheme><w:t>{hidden</w:t></w:styleLockTheme>",
            "<f:styleLockTheme xmlns:f=\"urn:foreign\" w:val=\"1\"/>")) {
            support.rejectChanged(source, "word/settings.xml", "styleLockTheme") { it.replace(node, replacement) }
        }
    }
}
