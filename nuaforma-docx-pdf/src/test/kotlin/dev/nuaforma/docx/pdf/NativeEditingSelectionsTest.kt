package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.nio.file.Path

class NativeEditingSelectionsTest {
    @TempDir lateinit var temporary: Path

    private data class Case(val folder: String, val golden: String, val pattern: String, val markers: Int, val pages: Int)

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `unused editing selections retain every Word paragraph table and field`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        val cases = listOf(
            Case("editing-selections/body-selection-control", "editing-selections/body-selection-control", "B[0-9]{2}_[A-Z]+", 2, 1),
            Case("editing-selections/body-selection-selected", "editing-selections/body-selection-control", "B[0-9]{2}_[A-Z]+", 2, 1),
            Case("editing-selections/cross-paragraph-control", "editing-selections/cross-paragraph-control", "B[0-9]{2}_[A-Z]+", 4, 1),
            Case("editing-selections/cross-paragraph-selected", "editing-selections/cross-paragraph-control", "B[0-9]{2}_[A-Z]+", 4, 1),
            Case("editing-selections/field-instruction-control", "editing-selections/field-instruction-control", "B[0-9]{2}_[A-Z]+", 2, 1),
            Case("editing-selections/field-instruction-selected", "editing-selections/field-instruction-control", "B[0-9]{2}_[A-Z]+", 2, 1),
            Case("editing-selections/field-result-control", "editing-selections/field-result-control", "B[0-9]{2}_[A-Z]+", 2, 1),
            Case("editing-selections/field-result-selected", "editing-selections/field-result-control", "B[0-9]{2}_[A-Z]+", 2, 1),
            Case("editing-selections/paragraph-control", "editing-selections/paragraph-control", "B[0-9]{2}_[A-Z]+", 3, 1),
            Case("editing-selections/paragraph-selected", "editing-selections/paragraph-control", "B[0-9]{2}_[A-Z]+", 3, 1),
            Case("editing-selections/table-selection-control", "editing-selections/table-selection-control", "B[0-9]{2}_[A-Z]+", 2, 1),
            Case("editing-selections/table-selection-selected", "editing-selections/table-selection-control", "B[0-9]{2}_[A-Z]+", 2, 1)
        )
        for (case in cases) {
            val profiles = if (paper == "a4" && case.folder in setOf("editing-selections/paragraph-selected", "editing-selections/field-instruction-selected"))
                listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard")
            support.verify("/${case.folder}/$paper/", profiles, Regex(case.pattern), case.markers,
                pageCount = case.pages, goldenDirectory = "/${case.golden}/$paper/")
        }
    }

    @Test
    fun `cleanup retains complete template bytes and reuses them without another change`() {
        val support = NativeWordFeatureSupport(temporary)
        for (folder in listOf("editing-selections/paragraph-selected/a4", "editing-selections/field-instruction-selected/letter", "editing-selections/table-selection-selected/a4")) {
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
    fun `invalid editing markers remain precise source errors`() {
        val support = NativeWordFeatureSupport(temporary)
        val source = "/editing-selections/paragraph-selected/a4/source.docx"
        for (replacement in listOf("<w:bookmarkStart w:id=\"701\" w:name=\"_GoBack\" extra=\"1\" />",
            "<f:bookmarkStart xmlns:f=\"urn:foreign\" w:id=\"701\" w:name=\"_GoBack\" />")) {
            support.rejectChanged(source, "word/document.xml", "bookmarkStart") {
                it.replace("<w:bookmarkStart w:id=\"701\" w:name=\"_GoBack\" />", replacement)
            }
        }
    }

}
