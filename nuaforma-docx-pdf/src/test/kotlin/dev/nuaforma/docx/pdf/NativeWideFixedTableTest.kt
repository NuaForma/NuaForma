package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.adapters.nativeengine.NativeDocxReader
import dev.nuaforma.pdf.PdfTable
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.nio.file.Path

class NativeWideFixedTableTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `fixed grids extending into the body margin retain Word geometry`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        for (mode in listOf("12", "15")) for (kind in listOf("omitted", "zero", "positive", "negative")) {
            val profiles = if (paper == "a4" && mode == "15" && kind == "zero")
                listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard")
            support.verify("/wide-fixed-tables/$mode-$kind/$paper/", profiles, Regex("I[0-9]{2}[AB]"), 6)
        }
    }

    @Test
    fun `cleanup retains every qualified fixed grid byte for byte`() {
        val support = NativeWordFeatureSupport(temporary)
        for (mode in listOf("12", "15")) for (kind in listOf("omitted", "zero", "positive", "negative")) for (paper in listOf("a4", "letter")) {
            val source = support.bytes("/wide-fixed-tables/$mode-$kind/$paper/source.docx")
            val first = ByteArrayOutputStream()
            val context = GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))
            assertTrue(support.provider.cleanTemplate(source.inputStream(), first, context).changes.isEmpty())
            assertArrayEquals(source, first.toByteArray())
            val second = ByteArrayOutputStream()
            assertTrue(support.provider.cleanTemplate(first.toByteArray().inputStream(), second, context).changes.isEmpty())
            assertArrayEquals(source, second.toByteArray())
        }
    }

    private fun rejected(support: NativeWordFeatureSupport, source: ByteArray, explanation: String) {
        for (profile in listOf("standard", "pdfa-3b")) {
            val output = ByteArrayOutputStream()
            val failure = assertThrows(GenerationException::class.java) { support.generate(source, profile, output) }
            assertEquals(0, output.size())
            assertEquals(RejectionReason.NATIVE_PDF_RENDER_REJECTED, failure.diagnostics.single().rejectionReason)
            assertTrue(failure.diagnostics.single().sourceContext?.explanation.orEmpty().contains(explanation), failure.diagnostics.toString())
        }
    }

    @Test
    fun `fixed tables cannot extend beyond the physical page or diverge from their declared grid`() {
        val support = NativeWordFeatureSupport(temporary)
        for (paper in listOf("a4", "letter")) {
            val source = "/wide-fixed-tables/15-zero/$paper/source.docx"
            val parts = PdfFixture.unzip(support.bytes(source)).toMutableMap()
            val original = parts.getValue("word/document.xml").decodeToString()
            val changed = original.replace(Regex("""(<w:tblW\b[^>]*\bw:w=")[0-9]+""")) { "${it.groupValues[1]}12000" }
                .replace(Regex("""(<w:(?:gridCol|tcW)\b[^>]*\bw:w=")[0-9]+""")) { "${it.groupValues[1]}6000" }
            assertNotEquals(original, changed)
            parts["word/document.xml"] = changed.toByteArray()
            rejected(support, PdfFixture.zip(parts), "Table exceeds its containing width")
            support.rejectChanged(source, "word/document.xml", "tbl") { xml ->
                val width = Regex("""<w:tblW\b[^>]*\bw:w="([0-9]+)"""").find(xml)!!.groups[1]!!
                xml.replaceRange(width.range, (width.value.toInt() + 1).toString())
            }
        }
    }

    @Test
    fun `nested cells and page bands do not inherit the body margin allowance`() {
        val support = NativeWordFeatureSupport(temporary)
        val parts = PdfFixture.unzip(support.bytes("/wide-fixed-tables/15-zero/a4/source.docx")).toMutableMap()
        val original = parts.getValue("word/document.xml").decodeToString()
        val table = Regex("<w:tbl>.*?</w:tbl>", RegexOption.DOT_MATCHES_ALL).find(original)!!.value
        val firstCell = Regex("<w:tc>.*?</w:tcPr>", RegexOption.DOT_MATCHES_ALL).find(original)!!
        parts["word/document.xml"] = original.replaceRange(firstCell.range, firstCell.value + table).toByteArray()
        val nested = PdfFixture.zip(parts)
        val model = NativeDocxReader(GenerationContext(), { _, _, _ -> "fixture" }).read(nested)
        val inner = model.blocks.filterIsInstance<PdfTable>().single().rows.first().cells.first().blocks.filterIsInstance<PdfTable>().single()
        assertEquals(0f, inner.rightOverflow)
        rejected(support, nested, "Table exceeds its containing width")

        val relationship = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        parts["word/document.xml"] = original.replace("<w:sectPr>", "<w:sectPr><w:headerReference xmlns:r=\"$relationship\" w:type=\"default\" r:id=\"wideHeader\"/>")
            .replace("<w:pgMar ", "<w:pgMar w:header=\"720\" ").toByteArray()
        parts["word/header1.xml"] = ("<w:hdr xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" + table + "</w:hdr>").toByteArray()
        parts["word/_rels/document.xml.rels"] = parts.getValue("word/_rels/document.xml.rels").decodeToString()
            .replace("</Relationships>", "<Relationship Id=\"wideHeader\" Type=\"$relationship/header\" Target=\"header1.xml\"/></Relationships>").toByteArray()
        parts["[Content_Types].xml"] = parts.getValue("[Content_Types].xml").decodeToString()
            .replace("</Types>", "<Override PartName=\"/word/header1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml\"/></Types>").toByteArray()
        val header = PdfFixture.zip(parts)
        val band = NativeDocxReader(GenerationContext(), { _, _, _ -> "fixture" }).read(header).flow!!.header!!
        assertEquals(0f, band.blocks.filterIsInstance<PdfTable>().single().rightOverflow)
        rejected(support, header, "Table exceeds its containing width")
    }

}
