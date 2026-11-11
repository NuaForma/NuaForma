package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.nio.file.Path

class NativeSequenceIdentityTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `mixed-case sequence names share increment repeat and restart state like Word`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        for (kind in listOf("canonical", "mixed")) {
            val profiles = if (kind == "mixed" && paper == "a4") listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b")
                else listOf("standard")
            support.verify("/sequence-identity/$kind/$paper/", profiles, Regex("C[0-9]_(?:BEFORE|AFTER)"), 10,
                goldenDirectory = "/sequence-identity/canonical/$paper/")
        }
    }

    @Test
    fun `cleanup retains original mixed-case instructions and stale cache bytes`() {
        val support = NativeWordFeatureSupport(temporary)
        val context = GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))
        for (paper in listOf("a4", "letter")) {
            val source = support.bytes("/sequence-identity/mixed/$paper/source.docx")
            val first = ByteArrayOutputStream()
            assertTrue(support.provider.cleanTemplate(source.inputStream(), first, context).changes.isEmpty())
            assertArrayEquals(source, first.toByteArray())
            val second = ByteArrayOutputStream()
            assertTrue(support.provider.cleanTemplate(first.toByteArray().inputStream(), second, context).changes.isEmpty())
            assertArrayEquals(source, second.toByteArray())
            assertTrue(support.generate(second.toByteArray(), "standard").isNotEmpty())
        }
    }
}
