package dev.nuaforma.docx.pdf

import org.apache.pdfbox.Loader
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class NativeCellEndMarkTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `hiding a blank final marker reduces only its line and respects a row floor`() {
        val visible = markers(mark = 72)
        val hidden = markers(mark = 72, toggle = "<w:hideMark/>")
        assertTrue(distance(visible) - distance(hidden) > 40f)
        val minimum = markers(mark = 72, toggle = "<w:hideMark/>", rowFloor = 300)
        assertEquals(15f, distance(minimum) - distance(hidden), .05f)
        assertEquals(visible, markers(mark = 72, toggle = """<w:hideMark w:val="off"/>"""))
        val retainedParagraph = markers(mark = 72, toggle = "<w:hideMark/>", precedingText = true)
        assertTrue(retainedParagraph.containsKey("FIRST_TEXT"))
        assertTrue(distance(retainedParagraph) - distance(hidden) > 10f)
    }

    @Test
    fun `the hidden empty marker contributes neither its line nor its paragraph spacing`() {
        for (preceding in listOf(false, true)) {
            val hidden = markers(mark = 72, toggle = "<w:hideMark/>", precedingText = preceding)
            val spacedHidden = markers(mark = 72, toggle = "<w:hideMark/>", precedingText = preceding, markerSpacing = 180)
            val spacedVisible = markers(mark = 72, precedingText = preceding, markerSpacing = 180)
            assertEquals(hidden, spacedHidden)
            assertTrue(distance(spacedVisible) - distance(spacedHidden) > 40f)
        }
    }

    @Test
    fun `hiding a large mark in a populated cell retains the visible text and its ordinary line`() {
        val ordinary = markers(mark = 22, populated = true)
        val hidden = markers(mark = 72, populated = true, toggle = "<w:hideMark/>")
        assertEquals(ordinary.keys, hidden.keys)
        assertTrue(hidden.containsKey("CELL_TEXT"))
        assertEquals(distance(ordinary), distance(hidden), .15f)
    }

    @Test
    fun `invalid original cell mark toggles are diagnosed on the cell property`() {
        val support = NativeWordFeatureSupport(temporary)
        for (invalid in listOf("""<w:hideMark val="1"/>""", """<w:hideMark w:val="unknown"/>""",
            "<w:hideMark/><w:hideMark/>", "<w:hideMark>text</w:hideMark>")) {
            support.rejectChanged(source, part, "hideMark") { fixture(it, invalid, 24, false, null, false) }
        }
    }

    private fun distance(markers: Map<String, NativeWordFeatureSupport.Position>) =
        markers.getValue("AFTER_CELL").y - markers.getValue("BEFORE_CELL").y

    private fun markers(mark: Int, toggle: String = "", populated: Boolean = false, rowFloor: Int? = null,
        precedingText: Boolean = false, markerSpacing: Int = 0): Map<String, NativeWordFeatureSupport.Position> {
        val support = NativeWordFeatureSupport(temporary)
        val parts = PdfFixture.unzip(support.bytes(source)).toMutableMap()
        parts[part] = fixture(parts.getValue(part).decodeToString(), toggle, mark, populated, rowFloor, precedingText, markerSpacing).toByteArray()
        return Loader.loadPDF(support.generate(PdfFixture.zip(parts), "standard")).use { pdf ->
            assertEquals(1, pdf.numberOfPages)
            support.positions(pdf, Regex("BEFORE_CELL|AFTER_CELL|CELL_TEXT|FIRST_TEXT"))
        }
    }

    private fun fixture(xml: String, toggle: String, mark: Int, populated: Boolean, rowFloor: Int?, precedingText: Boolean, markerSpacing: Int = 0): String {
        val previous = if (precedingText) paragraph("FIRST_TEXT", 22) else ""
        val cellContent = previous + paragraph(if (populated) "CELL_TEXT" else "", mark, markerSpacing)
        return xml.substringBefore("<w:body>") +
            """<w:body><w:tbl><w:tblPr><w:tblW w:w="4000" w:type="dxa"/><w:tblLayout w:type="fixed"/>""" +
            """<w:tblCellMar><w:top w:w="0" w:type="dxa"/><w:left w:w="0" w:type="dxa"/>""" +
            """<w:bottom w:w="0" w:type="dxa"/><w:right w:w="0" w:type="dxa"/></w:tblCellMar></w:tblPr>""" +
            """<w:tblGrid><w:gridCol w:w="4000"/></w:tblGrid>""" +
            row(paragraph("BEFORE_CELL", 22), "", 280) + row(cellContent, toggle, rowFloor) +
            row(paragraph("AFTER_CELL", 22), "", 280) + "</w:tbl><w:sectPr>" + xml.substringAfter("<w:sectPr>")
    }

    private fun row(content: String, toggle: String, height: Int?): String {
        val rowMinimum = height?.let { """<w:trPr><w:trHeight w:val="$it" w:hRule="atLeast"/></w:trPr>""" }.orEmpty()
        return """<w:tr>$rowMinimum<w:tc><w:tcPr><w:tcW w:type="dxa" w:w="4000"/>$toggle</w:tcPr>$content</w:tc></w:tr>"""
    }

    private fun paragraph(text: String, mark: Int, spacing: Int = 0): String {
        val run = if (text.isEmpty()) "" else """<w:r><w:rPr><w:sz w:val="22"/></w:rPr><w:t>$text</w:t></w:r>"""
        return """<w:p><w:pPr><w:spacing w:before="$spacing" w:after="$spacing" w:line="240" w:lineRule="auto"/>""" +
            """<w:rPr><w:sz w:val="$mark"/></w:rPr></w:pPr>$run</w:p>"""
    }

    private companion object {
        const val source = "/floating-drawings/control/a4/source.docx"
        const val part = "word/document.xml"
    }
}
