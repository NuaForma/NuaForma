package dev.nuaforma.docx.pdf

import org.apache.pdfbox.Loader
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class NativeFullSpanNoWrapTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `full-width no-wrap protects the combined width without locking each column`() {
        val support = NativeWordFeatureSupport(temporary)
        val parts = PdfFixture.unzip(support.bytes(source))
        val xml = parts.getValue(part).decodeToString()

        fun positions(toggle: String): Map<String, NativeWordFeatureSupport.Position> {
            val output = support.generate(PdfFixture.zip(parts + (part to fixture(xml, 3, toggle).toByteArray())), "standard")
            return Loader.loadPDF(output).use { support.positions(it, Regex("FIRSTCOL|SECONDCOL|THIRDCOL")) }
        }

        val control = positions("")
        val grown = positions("<w:noWrap/>")
        assertEquals(3, grown.size)
        assertTrue(control.getValue("THIRDCOL").x < grown.getValue("THIRDCOL").x - 5f)
        assertEquals(100f, grown.getValue("SECONDCOL").x - grown.getValue("FIRSTCOL").x, .05f)
        assertEquals(100f, grown.getValue("THIRDCOL").x - grown.getValue("SECONDCOL").x, .05f)
    }

    @Test
    fun `partial spanning with active width protection keeps the original rejection`() {
        val support = NativeWordFeatureSupport(temporary)
        support.rejectChanged(source, part, "noWrap") { fixture(it, 2, "<w:noWrap/>") }
    }

    private fun fixture(xml: String, span: Int, toggle: String): String {
        val first = cell(if (span == 3) 300 else 220, "MERGED", """<w:gridSpan w:val="$span"/>$toggle""") +
            if (span == 2) cell(100, "SPANTAIL") else ""
        val table = """<w:tbl><w:tblPr><w:tblW w:type="dxa" w:w="5600"/><w:tblLayout w:type="autofit"/>""" +
            """<w:tblCellMar><w:top w:w="40" w:type="dxa"/><w:left w:w="80" w:type="dxa"/>""" +
            """<w:bottom w:w="40" w:type="dxa"/><w:right w:w="80" w:type="dxa"/></w:tblCellMar></w:tblPr>""" +
            """<w:tblGrid><w:gridCol w:w="2000"/><w:gridCol w:w="2000"/><w:gridCol w:w="2000"/></w:tblGrid>""" +
            "<w:tr>$first</w:tr><w:tr>" + cell(100, "FIRSTCOL") + cell(100, "SECONDCOL") + cell(100, "THIRDCOL") +
            "</w:tr></w:tbl>"
        return xml.substringBefore("<w:body>") + "<w:body>$table<w:sectPr>" + xml.substringAfter("<w:sectPr>")
    }

    private fun cell(width: Int, text: String, props: String = ""): String =
        """<w:tc><w:tcPr><w:tcW w:type="dxa" w:w="${width * 20}"/>$props</w:tcPr>""" +
            """<w:p><w:pPr><w:widowControl w:val="0"/><w:spacing w:before="0" w:after="0" w:line="280" w:lineRule="exact"/></w:pPr>""" +
            "<w:r><w:t>$text</w:t></w:r></w:p></w:tc>"

    private companion object {
        const val source = "/floating-drawings/control/a4/source.docx"
        const val part = "word/document.xml"
    }
}
