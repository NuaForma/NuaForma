package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.nio.file.Path

class NativeParagraphStyleDefaultsTest {
    @TempDir lateinit var temporary: Path

    private data class Case(val folder: String, val golden: String, val markers: Int)

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `implicit defaults retain exact Word precedence and native pixels`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        val cases = listOf(
            Case("paragraph-style-defaults/body/explicit-custom", "paragraph-style-defaults/body/explicit-custom", 3),
            Case("paragraph-style-defaults/body/explicit-doc-defaults", "paragraph-style-defaults/body/explicit-doc-defaults", 3),
            Case("paragraph-style-defaults/body/explicit-normal", "paragraph-style-defaults/body/explicit-normal", 3),
            Case("paragraph-style-defaults/body/implicit-no-normal", "paragraph-style-defaults/body/explicit-doc-defaults", 3),
            Case("paragraph-style-defaults/body/implicit-no-normal-reordered", "paragraph-style-defaults/body/explicit-doc-defaults", 3),
            Case("paragraph-style-defaults/body/implicit-normal", "paragraph-style-defaults/body/explicit-normal", 3),
            Case("paragraph-style-defaults/body/implicit-normal-reordered", "paragraph-style-defaults/body/explicit-normal", 3),
            Case("paragraph-style-defaults/body/implicit-alias-name-normal", "paragraph-style-defaults/body/explicit-normal", 3),
            Case("paragraph-style-defaults/body/implicit-lowercase-id", "paragraph-style-defaults/body/explicit-normal", 3),
            Case("paragraph-style-defaults/body/implicit-lowercase-name", "paragraph-style-defaults/body/explicit-normal", 3),
            Case("paragraph-style-defaults/body/implicit-normal-disabled", "paragraph-style-defaults/body/explicit-normal", 3),
            Case("paragraph-style-defaults/body/explicit-name-normal-over-id", "paragraph-style-defaults/body/explicit-name-normal-over-id", 3),
            Case("paragraph-style-defaults/body/implicit-id-normal-other-name", "paragraph-style-defaults/body/explicit-doc-defaults", 3),
            Case("paragraph-style-defaults/body/implicit-name-normal-over-id", "paragraph-style-defaults/body/explicit-name-normal-over-id", 3),
            Case("paragraph-style-defaults/body/implicit-name-normal-whitespace", "paragraph-style-defaults/body/explicit-normal", 3),
            Case("paragraph-style-defaults/body/15-explicit-doc-defaults", "paragraph-style-defaults/body/15-explicit-doc-defaults", 3),
            Case("paragraph-style-defaults/body/15-explicit-normal", "paragraph-style-defaults/body/15-explicit-normal", 3),
            Case("paragraph-style-defaults/body/15-implicit-no-normal", "paragraph-style-defaults/body/15-explicit-doc-defaults", 3),
            Case("paragraph-style-defaults/body/15-implicit-normal", "paragraph-style-defaults/body/15-explicit-normal", 3),
            Case("paragraph-style-defaults/table/12-explicit-normal", "paragraph-style-defaults/table/12-explicit-normal", 18),
            Case("paragraph-style-defaults/table/12-implicit-doc-defaults", "paragraph-style-defaults/table/12-explicit-normal", 18),
            Case("paragraph-style-defaults/table/12-implicit-normal", "paragraph-style-defaults/table/12-explicit-normal", 18),
            Case("paragraph-style-defaults/body/name-whitespace-en-space", "paragraph-style-defaults/body/explicit-doc-defaults", 3),
            Case("paragraph-style-defaults/body/name-whitespace-narrow-nbsp", "paragraph-style-defaults/body/explicit-doc-defaults", 3),
            Case("paragraph-style-defaults/body/name-whitespace-nbsp", "paragraph-style-defaults/body/explicit-normal", 3),
            Case("paragraph-style-defaults/body/name-whitespace-tab", "paragraph-style-defaults/body/explicit-normal", 3),
            Case("paragraph-style-defaults/body/name-whitespace-wide", "paragraph-style-defaults/body/explicit-normal", 3)
        )
        for (case in cases) {
            val profiles = if (paper == "a4" && case.folder in setOf("paragraph-style-defaults/body/implicit-normal",
                "paragraph-style-defaults/body/implicit-no-normal", "paragraph-style-defaults/table/12-implicit-doc-defaults"))
                listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard")
            support.verify("/${case.folder}/$paper/", profiles,
                if (case.markers == 18) Regex("(?:PHYSICAL|PERCENT|IMPLICIT)_[123]_[12]") else Regex("P[0-9]{2}_[A-Z]+"),
                case.markers, golden = "/${case.golden}/$paper/page-1.png")
        }
    }

    @Test
    fun `cleanup retains implicit style definitions and source bytes`() {
        val support = NativeWordFeatureSupport(temporary)
        for (kind in listOf("implicit-normal", "implicit-no-normal", "implicit-name-normal-over-id")) {
            val source = support.bytes("/paragraph-style-defaults/body/$kind/a4/source.docx")
            val before = source.copyOf()
            val output = ByteArrayOutputStream()
            assertTrue(support.provider.cleanTemplate(source.inputStream(), output,
                GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))).changes.isEmpty())
            assertArrayEquals(source, output.toByteArray())
            assertArrayEquals(before, source)
            assertTrue(support.generate(output.toByteArray(), "standard").isNotEmpty())
        }
    }

    @Test
    fun `invalid original flags reject before binding and publish no partial PDF`() {
        val support = NativeWordFeatureSupport(temporary)
        val source = "/paragraph-style-defaults/body/implicit-normal/a4/source.docx"
        for (replacement in listOf("w:default=\"invalid\"", "default=\"1\"", "f:default=\"1\" xmlns:f=\"urn:foreign\"")) {
            support.rejectChanged(source, "word/styles.xml", "style") {
                it.replace("w:styleId=\"Normal\"", "w:styleId=\"Normal\" $replacement")
            }
        }
        support.rejectChanged(source, "word/styles.xml", "style") {
            it.replace("w:styleId=\"Normal\"", "w:styleId=\"Normal\" w:default=\"1\"")
                .replace("w:styleId=\"CustomRoot\"", "w:styleId=\"CustomRoot\" w:default=\"true\"")
        }
        support.rejectChanged(source, "word/styles.xml", "style") {
            it.replace("w:val=\"Custom Root\"", "w:val=\"Normal\"")
        }
    }

    @Test
    fun `qualified Normal name equivalence never hides divergent duplicate formatting`() {
        val support = NativeWordFeatureSupport(temporary)
        val source = "/paragraph-style-defaults/body/name-whitespace-tab/a4/source.docx"
        support.rejectChanged(source, "word/stylesWithEffects.xml", "styles") {
            it.replace("w:val=\"AA0000\"", "w:val=\"00AA00\"")
        }
        support.rejectChanged(source, "word/stylesWithEffects.xml", "styles") {
            it.replace("w:val=\"&#09;Normal&#09;\"", "w:val=\"\u2002Normal\u2002\"")
        }
    }

}
