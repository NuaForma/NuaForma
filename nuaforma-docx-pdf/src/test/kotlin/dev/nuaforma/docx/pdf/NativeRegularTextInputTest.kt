package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.nio.file.Path

class NativeRegularTextInputTest {
    @TempDir lateinit var temporary: Path

    private data class Case(val folder: String, val golden: String, val pattern: String, val markers: Int, val pages: Int)

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `current regular form text retains Word formatting including repeated page bands`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        val cases = listOf(
            Case("regular-text-inputs/body/plain-control", "regular-text-inputs/body/plain-control", "F[0-9]{2}_[A-Z]+", 2, 1),
            Case("regular-text-inputs/body/regular-cached", "regular-text-inputs/body/plain-control", "F[0-9]{2}_[A-Z]+", 2, 1),
            Case("regular-text-inputs/body/regular-disabled", "regular-text-inputs/body/plain-control", "F[0-9]{2}_[A-Z]+", 2, 1),
            Case("regular-text-inputs/body/regular-exit", "regular-text-inputs/body/plain-control", "F[0-9]{2}_[A-Z]+", 2, 1),
            Case("regular-text-inputs/body/regular-mergeformat", "regular-text-inputs/body/plain-control", "F[0-9]{2}_[A-Z]+", 2, 1),
            Case("regular-text-inputs/body/regular-no-default", "regular-text-inputs/body/plain-control", "F[0-9]{2}_[A-Z]+", 2, 1),
            Case("regular-text-inputs/body/regular-short-max", "regular-text-inputs/body/plain-control", "F[0-9]{2}_[A-Z]+", 2, 1),
            Case("regular-text-inputs/additional/absent-type-empty-format", "regular-text-inputs/body/plain-control", "F[0-9]{2}_[A-Z]+", 2, 1),
            Case("regular-text-inputs/additional/uppercase-format", "regular-text-inputs/body/plain-control", "F[0-9]{2}_[A-Z]+", 2, 1),
            Case("regular-text-inputs/metadata-variants/uppercase-spelling", "regular-text-inputs/body/plain-control", "F[0-9]{2}_[A-Z]+", 2, 1),
            Case("regular-text-inputs/metadata-variants/empty-editing-name", "regular-text-inputs/body/plain-control", "F[0-9]{2}_[A-Z]+", 2, 1),
            Case("regular-text-inputs/metadata-variants/both", "regular-text-inputs/body/plain-control", "F[0-9]{2}_[A-Z]+", 2, 1),
            Case("regular-text-inputs/bands/control", "regular-text-inputs/bands/control", "[FGH][0-9]{2}_[A-Z]+", 12, 2),
            Case("regular-text-inputs/bands/field", "regular-text-inputs/bands/control", "[FGH][0-9]{2}_[A-Z]+", 12, 2),
            Case("regular-text-inputs/metadata/metadata-only", "regular-text-inputs/body/plain-control", "F[0-9]{2}_[A-Z]+", 2, 1)
        )
        for (case in cases) {
            val profiles = if (paper == "a4" && case.folder in setOf("regular-text-inputs/body/regular-cached", "regular-text-inputs/bands/field"))
                listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard")
            support.verify("/${case.folder}/$paper/", profiles, Regex(case.pattern), case.markers,
                pageCount = case.pages, goldenDirectory = "/${case.golden}/$paper/")
        }
    }

    @Test
    fun `cleanup retains complete template bytes and reuses them without another change`() {
        val support = NativeWordFeatureSupport(temporary)
        for (folder in listOf("regular-text-inputs/body/regular-cached/a4", "regular-text-inputs/metadata/metadata-only/a4", "regular-text-inputs/metadata-variants/both/a4", "regular-text-inputs/bands/field/letter")) {
            val source = support.bytes("/$folder/source.docx")
            val original = source.copyOf()
            val output = ByteArrayOutputStream()
            val context = GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))
            assertTrue(support.provider.cleanTemplate(source.inputStream(), output, context).changes.isEmpty())
            assertArrayEquals(source, output.toByteArray())
            val second = ByteArrayOutputStream()
            assertTrue(support.provider.cleanTemplate(output.toByteArray().inputStream(), second, context).changes.isEmpty())
            assertArrayEquals(source, second.toByteArray())
            assertTrue(support.generate(second.toByteArray(), "standard").isNotEmpty())
            assertArrayEquals(original, source)
        }
    }

    @Test
    fun `active malformed or absent form state cannot publish cached text`() {
        val support = NativeWordFeatureSupport(temporary)
        val source = "/regular-text-inputs/body/regular-cached/a4/source.docx"
        for (replacement in listOf("<w:type w:val=\"date\" />", "<w:type w:val=\"calculated\" />",
            "<w:type w:val=\"regular\" unexpected=\"1\" />", "<w:type val=\"regular\" />")) {
            support.rejectChanged(source, "word/document.xml", "type") { it.replace("<w:type w:val=\"regular\" />", replacement) }
        }
        support.rejectChanged(source, "word/document.xml", "ffData") {
            it.replace("<w:textInput>", "<w:entryMacro w:val=\"NeverRun\" /><w:textInput>")
        }
        support.rejectChanged(source, "word/document.xml", "fldChar") {
            it.replace(Regex("<w:ffData>.*?</w:ffData>", RegexOption.DOT_MATCHES_ALL), "")
        }
        for (folder in listOf("body/regular-empty", "additional/five-en-spaces", "additional/five-en-control")) {
            val output = ByteArrayOutputStream()
            val failure = assertThrows(GenerationException::class.java) {
                support.generate(support.bytes("/regular-text-inputs/$folder/a4/source.docx"), "standard", output)
            }
            assertEquals(0, output.size())
            assertEquals(DiagnosticCodes.UNSUPPORTED_INPUT, failure.diagnostics.single().code)
            if (folder.endsWith("regular-empty")) {
                assertEquals("word/document.xml", failure.diagnostics.single().part)
                assertTrue(failure.diagnostics.single().sourceContext?.path.orEmpty().contains("fldChar"))
            }
        }
    }

}
