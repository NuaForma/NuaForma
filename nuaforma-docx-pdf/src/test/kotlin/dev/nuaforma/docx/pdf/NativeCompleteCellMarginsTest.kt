package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.PdfFixture
import dev.nuaforma.pdf.PdfMargins
import dev.nuaforma.pdf.PdfTable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NativeCompleteCellMarginsTest {
    private fun read(partial: Boolean): PdfTable {
        val parts = PdfFixture.parts()
        val settings = """<w:settings xmlns:w="${PdfFixture.W}"><w:compat>
            <w:compatSetting w:name="compatibilityMode" w:uri="http://schemas.microsoft.com/office/word" w:val="15"/>
            </w:compat></w:settings>"""
        parts["word/settings.xml"] = settings.toByteArray()
        val types = "[Content_Types].xml"
        val type = """<Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/>"""
        parts[types] = parts.getValue(types).decodeToString().replace("</Types>", "$type</Types>").toByteArray()
        val relationships = "word/_rels/document.xml.rels"
        val link = """<Relationship Id="settings" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings" Target="settings.xml"/>"""
        parts[relationships] = parts.getValue(relationships).decodeToString()
            .replace("</Relationships>", "$link</Relationships>").toByteArray()
        val sides = mapOf("top" to 20, "right" to 80, "bottom" to 40, "left" to 60)
        val margins = sides.entries.filterNot { partial && it.key == "left" }.joinToString("") { (name, width) ->
            """<w:$name w:type="dxa" w:w="$width"/>"""
        }
        val border = sides.keys.joinToString("") { name -> """<w:$name w:val="single" w:sz="4" w:color="000000"/>""" }
        val cell = """<w:tc><w:tcPr><w:tcW w:type="dxa" w:w="2000"/><w:tcMar>$margins</w:tcMar>
            <w:tcBorders>$border</w:tcBorders></w:tcPr><w:p><w:r><w:t>Cell</w:t></w:r></w:p></w:tc>"""
        val table = """<w:tbl><w:tblPr><w:tblW w:type="dxa" w:w="4000"/><w:tblLayout w:type="fixed"/></w:tblPr>
            <w:tblGrid><w:gridCol w:w="2000"/><w:gridCol w:w="2000"/></w:tblGrid><w:tr>$cell$cell</w:tr></w:tbl>"""
        val path = "word/document.xml"
        val paragraph = "<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Hello {name}</w:t></w:r></w:p>"
        parts[path] = parts.getValue(path).decodeToString().replace(paragraph, table).toByteArray()
        return NativeDocxReader(GenerationContext()) { _, _, _ -> "regular" }.read(PdfFixture.zip(parts)).blocks.single() as PdfTable
    }

    @Test
    fun `complete direct margins supply every bordered cell`() {
        val cells = read(partial = false).rows.single().cells
        assertEquals(listOf(PdfMargins(1f, 4f, 2f, 3f), PdfMargins(1f, 4f, 2f, 3f)), cells.map { it.margins })
        cells.forEach { assertNotNull(it.borders?.top) }
    }

    @Test
    fun `incomplete direct margins still require a table fallback`() {
        val error = assertThrows(NativeDocxReadException::class.java) { read(partial = true) }
        assertEquals("Explicit cell margins required for tables with cell borders", error.message)
    }
}
