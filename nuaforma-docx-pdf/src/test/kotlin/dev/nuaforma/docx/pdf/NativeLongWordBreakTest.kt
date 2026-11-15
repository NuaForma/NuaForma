package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.adapters.nativeengine.NativeDocxReader
import dev.nuaforma.pdf.PdfParagraph
import dev.nuaforma.pdf.PdfTable
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.nio.file.Path

class NativeLongWordBreakTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `oversized words retain fixed columns and match Word in regular bold and italic`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        for (mode in listOf("12", "15")) for (face in listOf("regular", "bold", "italic")) {
            val profiles = if (paper == "a4" && mode == "15" && face == "regular")
                listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard")
            support.verify("/long-word-breaking/$mode-$face/$paper/", profiles, Regex("b0[123]"), 3)
        }
    }

    @Test
    fun `cleanup retains all twelve templates without rewriting automatic line ends`() {
        val support = NativeWordFeatureSupport(temporary)
        for (mode in listOf("12", "15")) for (face in listOf("regular", "bold", "italic")) for (paper in listOf("a4", "letter")) {
            val source = support.bytes("/long-word-breaking/$mode-$face/$paper/source.docx")
            val first = ByteArrayOutputStream()
            assertTrue(support.provider.cleanTemplate(source.inputStream(), first,
                GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))).changes.isEmpty())
            assertArrayEquals(source, first.toByteArray())
            val second = ByteArrayOutputStream()
            assertTrue(support.provider.cleanTemplate(first.toByteArray().inputStream(), second,
                GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))).changes.isEmpty())
            assertArrayEquals(source, second.toByteArray())
        }
    }

    @Test
    fun `body fixed cells opt in without changing the grid or automatic width policy`() {
        val support = NativeWordFeatureSupport(temporary)
        val source = support.bytes("/long-word-breaking/15-regular/a4/source.docx")

        fun read(value: ByteArray) = NativeDocxReader(GenerationContext(), { _, _, _ -> "fixture" }).read(value)

        val fixed = read(source)
        val table = fixed.blocks.filterIsInstance<PdfTable>().single()
        assertEquals(35f, table.columnWidths.first())
        assertTrue(table.rows.flatMap { it.cells }.flatMap { it.blocks }.filterIsInstance<PdfParagraph>().all { it.breakLongWords })
        assertTrue(fixed.blocks.filterIsInstance<PdfParagraph>().none { it.breakLongWords })
        val parts = PdfFixture.unzip(source).toMutableMap()
        val xml = parts.getValue("word/document.xml").decodeToString()
        val changed = xml.replace("w:type=\"fixed\"", "w:type=\"autofit\"")
        assertNotEquals(xml, changed)
        parts["word/document.xml"] = changed.toByteArray()
        val automatic = read(PdfFixture.zip(parts)).blocks.filterIsInstance<PdfTable>().single()
        assertEquals(table.columnWidths, automatic.columnWidths)
        assertTrue(automatic.rows.flatMap { it.cells }.flatMap { it.blocks }.filterIsInstance<PdfParagraph>().none { it.breakLongWords })
    }

    @Test
    fun `oversized shaped words preserve an explicit development diagnostic`() {
        val support = NativeWordFeatureSupport(temporary)
        val parts = PdfFixture.unzip(support.bytes("/long-word-breaking/12-regular/a4/source.docx")).toMutableMap()
        val original = parts.getValue("word/document.xml").decodeToString()
        val changed = original.replace("<w:rFonts", "<w:kern w:val=\"1\"/><w:rFonts")
        assertNotEquals(original, changed)
        parts["word/document.xml"] = changed.toByteArray()
        for (profile in listOf("standard", "pdfa-3b")) {
            val output = ByteArrayOutputStream()
            val failure = assertThrows(GenerationException::class.java) { support.generate(PdfFixture.zip(parts), profile, output) }
            assertEquals(0, output.size())
            assertEquals("UNSUPPORTED_TEXT: Long-word breaking with font shaping is not qualified",
                failure.diagnostics.single().sourceContext?.explanation)
        }
    }

    @Test
    fun `a cell narrower than one glyph rejects with exact measurements and no PDF`() {
        val support = NativeWordFeatureSupport(temporary)
        for (paper in listOf("a4", "letter")) {
            val parts = PdfFixture.unzip(support.bytes("/long-word-breaking/15-regular/$paper/source.docx")).toMutableMap()
            val original = parts.getValue("word/document.xml").decodeToString()
            val widths = Regex("""<w:gridCol\b[^>]*\bw:w="([0-9]+)"""").findAll(original).map { it.groupValues[1].toInt() }.toList()
            assertEquals(2, widths.size)
            val total = widths.sum()
            var column = 0
            val changed = original.replace(Regex("""(<w:(?:gridCol|tcW)\b[^>]*\bw:w=")[0-9]+""")) {
                val width = if (column++ % 2 == 0) 40 else total - 40
                "${it.groupValues[1]}$width"
            }.replace("</w:tcPr>", "<w:tcMar><w:top w:w=\"0\" w:type=\"dxa\"/><w:left w:w=\"0\" w:type=\"dxa\"/><w:bottom w:w=\"0\" w:type=\"dxa\"/><w:right w:w=\"0\" w:type=\"dxa\"/></w:tcMar></w:tcPr>")
            assertNotEquals(original, changed)
            parts["word/document.xml"] = changed.toByteArray()
            for (profile in listOf("standard", "pdfa-3b")) {
                val output = ByteArrayOutputStream()
                val failure = assertThrows(GenerationException::class.java) { support.generate(PdfFixture.zip(parts), profile, output) }
                assertEquals(0, output.size())
                val explanation = failure.diagnostics.single().sourceContext?.explanation.orEmpty()
                val match = Regex("CONTENT_OVERFLOW: Word cluster width exceeds line width; required=([0-9.]+) pt; available=([0-9.]+) pt").matchEntire(explanation)
                assertNotNull(match, failure.diagnostics.toString())
                assertTrue(match!!.groupValues[1].toFloat() > match.groupValues[2].toFloat())
            }
        }
    }
}
