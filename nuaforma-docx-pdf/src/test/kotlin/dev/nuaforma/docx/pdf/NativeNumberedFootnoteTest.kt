package dev.nuaforma.docx.pdf

import org.apache.pdfbox.Loader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class NativeNumberedFootnoteTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun listNumberingAndFootnotesKeepTheirIndependentLabelsAndNavigation() {
        val support = NativeWordFeatureSupport(temporary)
        for (paper in listOf("a4", "letter")) {
            val source = numberedSource(support, paper)
            for (profile in listOf("standard", "pdfa-3b")) {
                Loader.loadPDF(support.generate(source, profile)).use { pdf ->
                    val positions = support.positions(pdf, Regex("""(?:CALL01|CALL12|1\.|2\.)"""))
                    val first = positions.getValue("1:CALL01")
                    val firstNumber = positions.getValue("1:1.")
                    val lastNumber = positions.getValue("2:2.")
                    assertEquals(18f, first.x - firstNumber.x, .2f)
                    assertEquals(firstNumber.x, lastNumber.x, .2f)
                    val destinations = pdf.documentCatalog.names.dests.names
                    for (note in listOf(1, 12)) {
                        assertTrue(destinations.containsKey("source-footnote$note"))
                        assertTrue(destinations.containsKey("source-footnote$note-call"))
                    }
                    assertTrue(pdf.pages.sumOf { it.annotations.size } >= 24)
                }
            }
        }
    }

    private fun numberedSource(support: NativeWordFeatureSupport, paper: String): ByteArray {
        val parts = PdfFixture.unzip(support.bytes("/note-separator-formatting/control/$paper/source.docx")).toMutableMap()
        val lists = PdfFixture.unzip(support.bytes("/word-numbering-style/control0/$paper/source.docx"))
        parts["word/numbering.xml"] = lists.getValue("word/numbering.xml")
        val overrides = """<Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/>"""
        val relation = """<Relationship Id="numberingFixture" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering" Target="numbering.xml"/>"""
        insert(parts, "[Content_Types].xml", "</Types>", overrides)
        insert(parts, "word/_rels/document.xml.rels", "</Relationships>", relation)
        val properties = """<w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr>"""
        val part = "word/document.xml"
        var document = parts.getValue(part).decodeToString()
        for (mark in listOf("CALL01", "CALL12")) {
            val start = "<w:p><w:r><w:t>$mark</w:t>"
            assertTrue(document.contains(start), mark)
            document = document.replaceFirst(start, "<w:p>$properties<w:r><w:t>$mark</w:t>")
        }
        parts[part] = document.toByteArray()
        return PdfFixture.zip(parts)
    }

    private fun insert(parts: MutableMap<String, ByteArray>, name: String, before: String, value: String) {
        val xml = parts.getValue(name).decodeToString()
        assertTrue(xml.contains(before), name)
        parts[name] = xml.replaceFirst(before, "$value$before").toByteArray()
    }
}
