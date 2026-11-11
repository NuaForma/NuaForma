package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.nio.file.Path

class NativeCellPaginationDefaultsTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `qualified cell defaults retain Word page assignments and immutable control pixels`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        for (remaining in listOf(30, 110)) for (kind in listOf("plain", "widows", "keep-lines")) {
            val profiles = if (paper == "a4" && remaining == 30 && kind != "plain")
                listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard")
            support.verify("/cell-pagination-defaults/$kind-$remaining/$paper/", profiles, Regex("T[0-7]_abc"), 8,
                pageCount = 2, goldenDirectory = "/cell-pagination-defaults/plain-$remaining/$paper/")
        }
    }

    @Test
    fun `cleanup retains original cell flags without replacing template text`() {
        val support = NativeWordFeatureSupport(temporary)
        val context = GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))
        for (kind in listOf("plain", "widows", "keep-lines")) {
            val source = support.bytes("/cell-pagination-defaults/$kind-30/a4/source.docx")
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
    fun `effective keep-next and page breaks remain unsupported rather than silently ignored`() {
        val support = NativeWordFeatureSupport(temporary)
        for (paper in listOf("a4", "letter")) for (remaining in listOf(30, 110)) {
            val source = support.bytes("/cell-pagination-defaults/keep-next-$remaining/$paper/source.docx")
            val output = ByteArrayOutputStream()
            assertThrows(GenerationException::class.java) { support.generate(source, "standard", output) }
            assertEquals(0, output.size())
        }
        val parts = PdfFixture.unzip(support.bytes("/cell-pagination-defaults/plain-30/a4/source.docx")).toMutableMap()
        val document = parts.getValue("word/document.xml").decodeToString()
        val insertion = document.indexOf("<w:pPr>", document.indexOf("</w:tcPr>")) + "<w:pPr>".length
        assertTrue(insertion >= "<w:pPr>".length)
        parts["word/document.xml"] = document.replaceRange(insertion, insertion, "<w:pageBreakBefore />").toByteArray()
        val output = ByteArrayOutputStream()
        assertThrows(GenerationException::class.java) { support.generate(PdfFixture.zip(parts), "standard", output) }
        assertEquals(0, output.size())
    }

    @Test
    fun `original malformed flags cannot be discarded during Word binding`() {
        val support = NativeWordFeatureSupport(temporary)
        for ((kind, name) in listOf("widows" to "widowControl", "keep-lines" to "keepLines")) {
            val source = "/cell-pagination-defaults/$kind-30/a4/source.docx"
            for (bad in listOf("<w:$name w:val=\"invalid\" />", "<w:$name /><w:$name />",
                "<w:$name val=\"true\" />", "<x:$name xmlns:x=\"urn:future\" />")) {
                support.rejectChanged(source, "word/document.xml", name) { it.replace("<w:$name />", bad) }
            }
        }
    }
}
