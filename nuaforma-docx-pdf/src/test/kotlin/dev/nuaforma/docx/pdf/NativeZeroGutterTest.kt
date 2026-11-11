package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.nio.file.Path

class NativeZeroGutterTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `zero-width gutter flags preserve ordinary and mirrored physical pages`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        for (layout in listOf("ordinary", "mirrored")) {
            val variants = listOf("control", "rtl-enabled", "rtl-disabled") +
                if (layout == "ordinary") listOf("top-enabled", "top-disabled") else emptyList()
            for (kind in variants) {
                val profiles = if (kind == "rtl-enabled" && paper == "a4")
                    listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard")
                val golden = if (layout == "ordinary") "/preferred-table-widths/$paper/" else "/mirror-indents/$paper-flip/"
                support.verify("/zero-gutter/$layout/$kind/$paper/", profiles, Regex("(?!)"), 0,
                    pageCount = if (layout == "ordinary") 1 else 4, goldenDirectory = golden)
            }
        }
    }

    @Test
    fun `cleanup retains positional preferences and complete source bytes`() {
        val support = NativeWordFeatureSupport(temporary)
        val context = GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))
        for (path in listOf("ordinary/rtl-enabled", "ordinary/top-enabled", "mirrored/rtl-enabled")) {
            val source = support.bytes("/zero-gutter/$path/a4/source.docx")
            val output = ByteArrayOutputStream()
            assertTrue(support.provider.cleanTemplate(source.inputStream(), output, context).changes.isEmpty())
            assertArrayEquals(source, output.toByteArray())
            val second = ByteArrayOutputStream()
            assertTrue(support.provider.cleanTemplate(output.toByteArray().inputStream(), second, context).changes.isEmpty())
            assertArrayEquals(output.toByteArray(), second.toByteArray())
        }
    }

    @Test
    fun `malformed position flags cannot disappear during binding`() {
        val support = NativeWordFeatureSupport(temporary)
        for ((kind, part, element) in listOf(Triple("rtl", "word/document.xml", "rtlGutter"),
            Triple("top", "word/settings.xml", "gutterAtTop"))) {
            val source = "/zero-gutter/ordinary/$kind-enabled/a4/source.docx"
            val node = "<w:$element w:val=\"1\"/>"
            for (replacement in listOf("<w:$element w:val=\"maybe\"/>", "$node$node",
                "<w:$element w:val=\"1\" unexpected=\"1\"/>",
                "<w:$element><w:t>{hidden</w:t></w:$element>",
                "<f:$element xmlns:f=\"urn:foreign\" w:val=\"1\"/>")) {
                support.rejectChanged(source, part, element) { it.replace(node, replacement) }
            }
        }
    }

    @Test
    fun `positive gutter widths remain unsupported for either positional flag`() {
        val support = NativeWordFeatureSupport(temporary)
        for (kind in listOf("rtl", "top")) {
            val source = "/zero-gutter/ordinary/$kind-enabled/a4/source.docx"
            support.rejectChanged(source, "word/document.xml", "pgMar") {
                if (it.contains("w:gutter=\"0\"")) it.replaceFirst("w:gutter=\"0\"", "w:gutter=\"120\"")
                else it.replaceFirst("<w:pgMar", "<w:pgMar w:gutter=\"120\"")
            }
        }
    }
}
