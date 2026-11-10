package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.nio.file.Path

class NativeSequenceFormattingTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `sequence code and stored formatting retain their independent Word controls`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        val controls = listOf("complex-default", "complex-merge", "complex-auto", "complex-merge-auto", "simple")
        val fields = controls.associateWith { it } + mapOf("complex-charformat" to "complex-default",
            "complex-split" to "complex-default", "complex-merge-split" to "complex-merge", "simple-merge" to "complex-merge")
        for (kind in controls) support.verify("/sequence-code-formatting/controls/$kind/$paper/", listOf("standard"),
            Regex("F[0-9]{2}_[A-Z]+"), 2)
        for ((kind, golden) in fields) {
            val profiles = if (paper == "a4" && kind in setOf("complex-default", "complex-merge"))
                listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard")
            support.verify("/sequence-code-formatting/fields/$kind/$paper/", profiles, Regex("F[0-9]{2}_[A-Z]+"), 2,
                goldenDirectory = "/sequence-code-formatting/controls/$golden/$paper/")
        }
    }

    @Test
    fun `cleanup retains sequence codes and stale cache bytes without evaluating the template`() {
        val support = NativeWordFeatureSupport(temporary)
        val context = GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))
        for (kind in listOf("complex-default", "complex-charformat", "complex-merge-split", "simple")) {
            val source = support.bytes("/sequence-code-formatting/fields/$kind/a4/source.docx")
            val original = source.copyOf()
            val first = ByteArrayOutputStream()
            assertTrue(support.provider.cleanTemplate(source.inputStream(), first, context).changes.isEmpty())
            assertArrayEquals(source, first.toByteArray())
            val second = ByteArrayOutputStream()
            assertTrue(support.provider.cleanTemplate(first.toByteArray().inputStream(), second, context).changes.isEmpty())
            assertArrayEquals(source, second.toByteArray())
            assertTrue(support.generate(second.toByteArray(), "standard").isNotEmpty())
            assertArrayEquals(original, source)
        }
    }

    @Test
    fun `conflicting unsupported or malformed original code formatting cannot publish output`() {
        val support = NativeWordFeatureSupport(temporary)
        val source = "/sequence-code-formatting/fields/complex-default/a4/source.docx"
        for (code in listOf("SEQ StyleProbe \\* CHARFORMAT \\* MERGEFORMAT", "SEQ StyleProbe \\* CHARFORMAT \\* CHARFORMAT",
            "PAGE \\* CHARFORMAT", "SEQ StyleProbe \\* UnknownFormat")) {
            support.rejectChanged(source, "word/document.xml", "fldChar") { it.replace("SEQ StyleProbe", code) }
        }
        for (replacement in listOf("<w:sz val=\"28\" />", "<x:sz xmlns:x=\"urn:future\" w:val=\"28\" />",
            "<w:sz w:val=\"28\" /><w:b w:val=\"invalid\" />", "<w:sz w:val=\"28\" /><w:sz w:val=\"28\" />")) {
            support.rejectChanged(source, "word/document.xml", "rPr") { it.replace("<w:sz w:val=\"28\" />", replacement) }
        }
        support.rejectChanged("/sequence-code-formatting/fields/simple/a4/source.docx", "word/document.xml", "fldSimple") {
            it.replace("SEQ StyleProbe", "SEQ StyleProbe \\* CHARFORMAT")
        }
    }
}
