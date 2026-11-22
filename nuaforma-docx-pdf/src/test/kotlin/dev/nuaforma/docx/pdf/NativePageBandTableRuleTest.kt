package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.nativeengine.NativeDocxReadException
import dev.nuaforma.docx.pdf.adapters.nativeengine.NativeDocxReader
import dev.nuaforma.pdf.PdfTable
import dev.nuaforma.pdf.PdfTableWidthPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NativePageBandTableRuleTest {
    @Test
    fun `a single inherited footer rule retains its saved column width`() {
        val block = read(rule("top"))
        assertEquals(PdfTableWidthPolicy.FIXED, block.widthPolicy)
        assertEquals(listOf(252f), block.columnWidths)
        val borders = requireNotNull(block.rows.single().cells.single().borders)
        assertNotNull(borders.top)
        assertEquals(.5f, borders.top?.border?.width)
        assertNull(borders.left)
        assertNull(borders.right)
        assertNull(borders.bottom)
    }

    @Test
    fun `a sparse footer rule on the other side still fails with a source diagnostic`() {
        val error = assertThrows(NativeDocxReadException::class.java) { read(rule("bottom")) }
        assertEquals("word/styles.xml", error.part)
    }

    private fun read(border: String): PdfTable {
        val parts = PdfFixture.parts()
        val path = "word/document.xml"
        val rels = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        val kinds = "application/vnd.openxmlformats-officedocument.wordprocessingml"
        parts[path] = parts.getValue(path).decodeToString().replace("<w:sectPr>",
            """<w:sectPr><w:footerReference xmlns:r="$rels" w:type="default" r:id="footer"/>""")
            .replace("<w:pgMar ", """<w:pgMar w:header="0" w:footer="720" """).toByteArray()
        val relations = "word/_rels/document.xml.rels"
        parts[relations] = parts.getValue(relations).decodeToString().replace("</Relationships>",
            """<Relationship Id="footer" Type="$rels/footer" Target="footer1.xml"/>""" +
                """<Relationship Id="settings" Type="$rels/settings" Target="settings.xml"/></Relationships>""").toByteArray()
        val types = "[Content_Types].xml"
        parts[types] = parts.getValue(types).decodeToString().replace("</Types>",
            """<Override PartName="/word/footer1.xml" ContentType="$kinds.footer+xml"/>""" +
                """<Override PartName="/word/settings.xml" ContentType="$kinds.settings+xml"/></Types>""").toByteArray()
        val w = PdfFixture.W
        parts["word/settings.xml"] = """<w:settings xmlns:w="$w"><w:compat>
            <w:compatSetting w:name="compatibilityMode" w:uri="http://schemas.microsoft.com/office/word" w:val="15"/>
            </w:compat></w:settings>""".toByteArray()
        parts["word/footer1.xml"] = """<w:ftr xmlns:w="$w"><w:tbl><w:tblPr><w:tblStyle w:val="FooterRule"/>
            <w:tblW w:type="auto" w:w="0"/>
            <w:tblCellMar><w:top w:type="dxa" w:w="0"/><w:left w:type="dxa" w:w="108"/>
            <w:bottom w:type="dxa" w:w="0"/><w:right w:type="dxa" w:w="108"/></w:tblCellMar>
            </w:tblPr><w:tblGrid><w:gridCol w:w="5040"/></w:tblGrid><w:tr><w:tc>
            <w:tcPr><w:tcW w:type="dxa" w:w="5040"/></w:tcPr>
            <w:p><w:pPr><w:spacing w:line="240" w:lineRule="exact" w:after="0"/></w:pPr>
            <w:r><w:t>Footer</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:ftr>""".toByteArray()
        val style = """<w:style w:type="table" w:styleId="FooterRule"><w:name w:val="Footer Rule"/>
            <w:tblPr><w:tblBorders>$border</w:tblBorders></w:tblPr></w:style>"""
        val styles = "word/styles.xml"
        parts[styles] = parts.getValue(styles).decodeToString().replace("</w:styles>", style + "</w:styles>").toByteArray()
        val source = PdfFixture.zip(parts)
        val result = NativeDocxReader(GenerationContext()) { _, _, _ -> "regular" }.read(source)
        return requireNotNull(result.pageSequences.single().flow?.footer).blocks.single() as PdfTable
    }

    private fun rule(side: String) = """<w:$side w:val="single" w:sz="4" w:color="000000"/>"""
}
