package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.PdfFixture
import dev.nuaforma.pdf.PdfColor
import dev.nuaforma.pdf.PdfTable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NativeIndependentTableEdgesTest {
    private fun read(width: Int? = null, mode: Int = 15): PdfTable {
        val parts = PdfFixture.parts()
        val settings = """<w:settings xmlns:w="${PdfFixture.W}"><w:compat>
            <w:compatSetting w:name="compatibilityMode" w:uri="http://schemas.microsoft.com/office/word" w:val="$mode"/>
            </w:compat></w:settings>"""
        parts["word/settings.xml"] = settings.toByteArray()
        val types = "[Content_Types].xml"
        val type = """<Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/>"""
        parts[types] = parts.getValue(types).decodeToString().replace("</Types>", "$type</Types>").toByteArray()
        val relationships = "word/_rels/document.xml.rels"
        val link = """<Relationship Id="settings" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings" Target="settings.xml"/>"""
        parts[relationships] = parts.getValue(relationships).decodeToString()
            .replace("</Relationships>", "$link</Relationships>").toByteArray()
        val tcW = width?.let { """<w:tcW w:type="dxa" w:w="$it"/>""" }.orEmpty()
        val cell = "<w:tc><w:tcPr>$tcW</w:tcPr><w:p><w:r><w:t>Cell</w:t></w:r></w:p></w:tc>"
        val row = "<w:tr>$cell$cell</w:tr>"
        val table = """<w:tbl><w:tblPr><w:tblW w:w="4000" w:type="dxa"/><w:tblLayout w:type="fixed"/>
            <w:tblBorders><w:top w:val="single" w:sz="8" w:color="225588"/>
            <w:left w:val="single" w:sz="16" w:color="882255"/><w:bottom w:val="single" w:sz="10" w:color="449922"/>
            <w:right w:val="single" w:sz="6" w:color="BB2299"/><w:insideH w:val="single" w:sz="4" w:color="0066DD"/>
            <w:insideV w:val="single" w:sz="2" w:color="DD6600"/></w:tblBorders>
            </w:tblPr><w:tblGrid><w:gridCol w:w="2000"/><w:gridCol w:w="2000"/></w:tblGrid>$row$row</w:tbl>"""
        val path = "word/document.xml"
        val paragraph = "<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Hello {name}</w:t></w:r></w:p>"
        parts[path] = parts.getValue(path).decodeToString().replace(paragraph, table).toByteArray()
        return NativeDocxReader(GenerationContext()) { _, _, _ -> "regular" }.read(PdfFixture.zip(parts)).blocks.single() as PdfTable
    }

    @Test
    fun `independent colored edges retain their own widths and leave clearance for cell text`() {
        for (width in listOf(null, 2000)) {
            val table = read(width)
            assertEquals(listOf(100f, 100f), table.columnWidths)
            val first = table.rows[0].cells[0]
            val next = table.rows[1].cells[0]
            val edges = requireNotNull(first.borders)
            assertEquals(2f, edges.left?.border?.width)
            assertEquals(PdfColor(136, 34, 85), edges.left?.border?.color)
            assertEquals(.25f, edges.right?.border?.width)
            assertEquals(edges.bottom, next.borders?.top)
            assertEquals(1f, first.margins?.left)
            assertEquals(.5f, first.margins?.right)
        }
    }

    @Test
    fun `legacy edges and explicit fixed grid disagreements retain their diagnostics`() {
        val legacy = assertThrows(NativeDocxReadException::class.java) { read(mode = 12) }
        assertEquals("Table edges with independent visibility require a flat modern body table or a single qualified page-band rule", legacy.message)
        val mismatch = assertThrows(NativeDocxReadException::class.java) { read(width = 1900) }
        assertEquals("Inconsistent cell width", mismatch.message)
    }
}
