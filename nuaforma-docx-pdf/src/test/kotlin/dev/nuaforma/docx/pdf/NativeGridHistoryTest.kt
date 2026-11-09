package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.nio.file.Path

class NativeGridHistoryTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `only the current grid renders and historical metadata never becomes template input`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        for (variant in listOf("fractional-history", "different-column-count")) {
            support.verify("/grid-history/$variant/$paper/", if (variant == "fractional-history" && paper == "a4")
                listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard"),
                Regex("(?!)"), 0, golden = "/preferred-table-widths/$paper/page-1.png")
        }
    }

    @Test
    fun `cleanup preserves canonical historical grids and source data in a reusable DOCX`() {
        val support = NativeWordFeatureSupport(temporary)
        for (paper in listOf("a4", "letter")) {
            val source = support.bytes("/grid-history/fractional-history/$paper/source.docx")
            val before = source.copyOf()
            val cleaned = ByteArrayOutputStream()
            val context = GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))
            val report = support.provider.cleanTemplate(source.inputStream(), cleaned, context)
            assertTrue(report.changes.isNotEmpty())
            assertTrue(report.changes.all { it.action == TemplateCleanupAction.RESTORE_REQUIRED_STRUCTURE })
            val parts = PdfFixture.unzip(cleaned.toByteArray())
            for ((part, bytes) in PdfFixture.unzip(source)) if (part != "word/document.xml") assertArrayEquals(bytes, parts[part], part)
            val root = ConversionXml(context).readPart(parts.getValue("word/document.xml"), "word/document.xml")
            val history = root.descendants().single { it.name.localPart == "tblGridChange" }
            val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            assertEquals("Synthetic {inactive", history.attribute(word, "author"))
            assertEquals(listOf("819", "409"), history.descendants().filter { it.name.localPart == "gridCol" }.map { it.attribute(word, "w") }.toList())
            val again = ByteArrayOutputStream()
            assertTrue(support.provider.cleanTemplate(cleaned.toByteArray().inputStream(), again, context).changes.isEmpty())
            assertArrayEquals(cleaned.toByteArray(), again.toByteArray())
            assertTrue(support.generate(cleaned.toByteArray(), "standard").isNotEmpty())
            assertArrayEquals(before, source)
        }
    }

    @Test
    fun `invalid history is diagnosed before binding without removing current data silently`() {
        val support = NativeWordFeatureSupport(temporary)
        val source = "/grid-history/fractional-history/a4/source.docx"
        for ((before, after, element) in listOf(
            Triple("w:id=\"7\"", "w:id=\"-1\"", "tblGridChange"),
            Triple("w:w=\"819.2\"", "w:w=\"288000.1\"", "gridCol"),
            Triple("w:w=\"819.2\"", "w:w=\"NaN\"", "gridCol"),
            Triple("<w:tblGridChange", "<w:tblGridChange unexpected=\"1\"", "tblGridChange"),
            Triple("<w:gridCol w:w=\"819.2\"/>", "<w:ins/>", "ins"))) {
            support.rejectChanged(source, "word/document.xml", element) { it.replaceFirst(before, after) }
        }
    }
}
