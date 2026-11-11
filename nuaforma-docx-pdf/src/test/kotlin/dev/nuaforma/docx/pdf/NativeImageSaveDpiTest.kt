package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.nio.file.Path

class NativeImageSaveDpiTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `save resolution preferences retain body and page-band picture geometry and pixels`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        for (kind in listOf("control", "dpi96", "dpi300", "dpi600", "no-compress-control", "no-compress-dpi300")) {
            val profiles = if (kind == "dpi300" && paper == "a4")
                listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard")
            support.verify("/passive-print-metadata/image-save-dpi/$kind/$paper/", profiles, Regex("(?!)"), 0,
                golden = "/image-compression-states/$paper/page-1.png")
        }
    }

    @Test
    fun `cleanup preserves original resolution settings and every embedded image byte`() {
        val support = NativeWordFeatureSupport(temporary)
        val context = GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))
        for (kind in listOf("dpi96", "dpi300", "dpi600", "no-compress-dpi300")) {
            val source = support.bytes("/passive-print-metadata/image-save-dpi/$kind/a4/source.docx")
            val output = ByteArrayOutputStream()
            assertTrue(support.provider.cleanTemplate(source.inputStream(), output, context).changes.isEmpty())
            assertArrayEquals(source, output.toByteArray())
            val original = PdfFixture.unzip(source)
            val cleaned = PdfFixture.unzip(output.toByteArray())
            val images = original.filterKeys { it.startsWith("word/media/") }
            assertTrue(images.isNotEmpty())
            images.forEach { (part, bytes) -> assertArrayEquals(bytes, cleaned[part], part) }
            val second = ByteArrayOutputStream()
            assertTrue(support.provider.cleanTemplate(output.toByteArray().inputStream(), second, context).changes.isEmpty())
            assertArrayEquals(output.toByteArray(), second.toByteArray())
        }
    }

    @Test
    fun `invalid original DPI metadata cannot be lost by typed binding`() {
        val support = NativeWordFeatureSupport(temporary)
        val source = "/passive-print-metadata/image-save-dpi/dpi300/a4/source.docx"
        val node = "<w14:defaultImageDpi w14:val=\"300\"/>"
        for (value in listOf("0", "-1", "2147483648", "300.0", "NaN", "1e3")) {
            support.rejectChanged(source, "word/settings.xml", "defaultImageDpi") {
                it.replace(node, "<w14:defaultImageDpi w14:val=\"$value\"/>")
            }
        }
        for (replacement in listOf("<w14:defaultImageDpi/>", "$node$node",
            "<w14:defaultImageDpi w14:val=\"300\" unexpected=\"1\"/>",
            "<w14:defaultImageDpi w14:val=\"300\"><w:t>{hidden</w:t></w14:defaultImageDpi>",
            "<w:defaultImageDpi w:val=\"300\"/>")) {
            support.rejectChanged(source, "word/settings.xml", "defaultImageDpi") { it.replace(node, replacement) }
        }
    }
}
