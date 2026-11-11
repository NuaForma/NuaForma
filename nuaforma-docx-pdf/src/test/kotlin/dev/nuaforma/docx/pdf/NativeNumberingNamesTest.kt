package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.nio.file.Path

class NativeNumberingNamesTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `definition display names retain the exact Word numbering and existing pixels`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        for (kind in listOf("control", "ordinary", "brace", "unicode")) {
            val profiles = if (kind == "ordinary" && paper == "a4")
                listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard")
            support.verify("/passive-print-metadata/numbering-name/$kind/$paper/", profiles, Regex("N[0-9]{2}"), 3,
                golden = "/word-numbering-style/control0/$paper/page-1.png")
        }
    }

    @Test
    fun `cleanup retains inert names including literal braces and non-Latin metadata`() {
        val support = NativeWordFeatureSupport(temporary)
        val context = GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))
        for (kind in listOf("ordinary", "brace", "unicode")) {
            val source = support.bytes("/passive-print-metadata/numbering-name/$kind/a4/source.docx")
            val before = source.copyOf()
            val output = ByteArrayOutputStream()
            assertTrue(support.provider.cleanTemplate(source.inputStream(), output, context).changes.isEmpty())
            assertArrayEquals(source, output.toByteArray())
            val second = ByteArrayOutputStream()
            assertTrue(support.provider.cleanTemplate(output.toByteArray().inputStream(), second, context).changes.isEmpty())
            assertArrayEquals(output.toByteArray(), second.toByteArray())
            assertTrue(support.generate(second.toByteArray(), "standard").isNotEmpty())
            assertArrayEquals(before, source)
        }
    }

    @Test
    fun `invalid or active name payloads fail at the original numbering declaration`() {
        val support = NativeWordFeatureSupport(temporary)
        val source = "/passive-print-metadata/numbering-name/ordinary/a4/source.docx"
        val name = "<w:name w:val=\"Synthetic List\"/>"
        for (replacement in listOf("<w:name/>", "<w:name w:val=\"\"/>",
            "<w:name w:val=\"${"a".repeat(254)}\"/>", "$name$name",
            "<w:name w:val=\"Name\" unexpected=\"1\"/>",
            "<w:name w:val=\"Name\"><w:t>{hidden</w:t></w:name>",
            "<f:name xmlns:f=\"urn:foreign\" w:val=\"Name\"/>")) {
            support.rejectChanged(source, "word/numbering.xml", "name") { it.replace(name, replacement) }
        }
    }
}
