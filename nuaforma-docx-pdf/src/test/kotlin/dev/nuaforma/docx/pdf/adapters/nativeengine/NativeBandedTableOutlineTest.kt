package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.PdfFixture
import dev.nuaforma.pdf.PdfTable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NativeBandedTableOutlineTest {
    private fun read(baseCellBorders: Boolean = false, bandColor: String? = null): PdfTable {
        val parts = PdfFixture.parts()
        val outline = listOf("top", "left", "bottom", "right")
        fun strokes(color: String) = outline.joinToString("") { side ->
            """<w:$side w:val="single" w:sz="8" w:color="$color"/>"""
        }
        fun edges(color: String) = "<w:tcPr><w:tcBorders>${strokes(color)}</w:tcBorders></w:tcPr>"
        val policy = if (baseCellBorders) edges("245A98") else ""
        val band = bandColor?.let { """<w:tblStylePr w:type="band1Horz">${edges(it)}</w:tblStylePr>""" }.orEmpty()
        val tableStyle = """<w:style w:type="table" w:styleId="BandStroke"><w:name w:val="Band stroke"/>
            <w:tblPr><w:tblBorders>${strokes("245A98")}</w:tblBorders></w:tblPr>$policy$band</w:style>"""
        val stylePart = "word/styles.xml"
        parts[stylePart] = parts.getValue(stylePart).decodeToString().replace("</w:styles>", "$tableStyle</w:styles>").toByteArray()
        val contentTypes = "[Content_Types].xml"
        val contentType = """<Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/>"""
        parts[contentTypes] = parts.getValue(contentTypes).decodeToString().replace("</Types>", "$contentType</Types>").toByteArray()
        val relPart = "word/_rels/document.xml.rels"
        val settingLink = """<Relationship Id="settings" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings" Target="settings.xml"/>"""
        parts[relPart] = parts.getValue(relPart).decodeToString().replace("</Relationships>", "$settingLink</Relationships>").toByteArray()
        parts["word/settings.xml"] = """<w:settings xmlns:w="${PdfFixture.W}"><w:compat>
            <w:compatSetting w:name="compatibilityMode" w:uri="http://schemas.microsoft.com/office/word" w:val="15"/>
            </w:compat></w:settings>""".toByteArray()
        val rows = (1..4).joinToString("") { row ->
            val cells = (1..2).joinToString("") { col -> """<w:tc><w:tcPr><w:tcW w:w="2000" w:type="dxa"/></w:tcPr>
                <w:p><w:r><w:t>R${row}C$col</w:t></w:r></w:p></w:tc>""" }
            "<w:tr>$cells</w:tr>"
        }
        val margins = outline.joinToString("") { """<w:$it w:w="100" w:type="dxa"/>""" }
        val table = """<w:tbl><w:tblPr><w:tblStyle w:val="BandStroke"/><w:tblW w:w="4000" w:type="dxa"/>
            <w:tblLayout w:type="fixed"/><w:tblStyleRowBandSize w:val="1"/><w:tblCellMar>$margins</w:tblCellMar>
            <w:tblLook w:val="0400" w:firstRow="0" w:lastRow="0" w:firstColumn="0" w:lastColumn="0" w:noHBand="0" w:noVBand="1"/>
            </w:tblPr><w:tblGrid><w:gridCol w:w="2000"/><w:gridCol w:w="2000"/></w:tblGrid>$rows</w:tbl>"""
        val docPart = "word/document.xml"
        val defaultBody = "<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Hello {name}</w:t></w:r></w:p>"
        parts[docPart] = parts.getValue(docPart).decodeToString().replace(defaultBody, table).toByteArray()
        return NativeDocxReader(GenerationContext()) { _, _, _ -> "regular" }.read(PdfFixture.zip(parts)).blocks.single() as PdfTable
    }

    @Test
    fun horizontalBandsPaintAcrossRowsWithoutInventingAnInteriorVerticalLine() {
        val table = read(bandColor = "245A98")
        assertEquals(listOf(true, false, true, false), table.rows.map { it.cells.first().borders?.top != null })
        assertEquals(listOf(true, false, true, true), table.rows.map { it.cells.first().borders?.bottom != null })
        for (row in table.rows) {
            assertNotNull(row.cells.first().borders?.left)
            assertNotNull(row.cells.last().borders?.right)
            assertNull(row.cells.first().borders?.right)
            assertNull(row.cells.last().borders?.left)
        }
    }

    @Test
    fun baseCellStyleWithTheSameStrokeKeepsOnlyTheTableOutline() {
        val table = read(baseCellBorders = true)
        assertEquals(listOf(true, false, false, false), table.rows.map { it.cells.first().borders?.top != null })
        assertEquals(listOf(false, false, false, true), table.rows.map { it.cells.first().borders?.bottom != null })
        assertNull(table.rows[1].cells.first().borders?.right)
    }

    @Test
    fun conflictingBandStrokeKeepsTheDiagnostic() {
        assertThrows(NativeDocxReadException::class.java) { read(bandColor = "9A3030") }
    }
}
