package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.nativeengine.NativeDocxReader
import dev.nuaforma.pdf.PdfTable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NativeMergedRowSplitFlagTest {
    @Test
    fun `Word permission to split is ignored for vertically merged tables only`() {
        val parts = PdfFixture.parts()
        val path = "word/document.xml"
        val source = parts.getValue(path).decodeToString()

        for (merged in listOf(false, true)) for (cantSplit in listOf(false, true)) {
            val first = if (merged) """<w:vMerge w:val="restart"/>""" else ""
            val second = if (merged) "<w:vMerge/>" else ""
            val properties = if (cantSplit) "<w:trPr><w:cantSplit/></w:trPr>" else ""
            val table = """<w:tbl><w:tblPr><w:tblW w:type="dxa" w:w="3600"/><w:tblLayout w:type="fixed"/>
                <w:tblCellMar><w:top w:w="0" w:type="dxa"/><w:bottom w:w="0" w:type="dxa"/>
                <w:left w:w="108" w:type="dxa"/><w:right w:w="108" w:type="dxa"/></w:tblCellMar></w:tblPr>
                <w:tblGrid><w:gridCol w:w="1800"/><w:gridCol w:w="1800"/></w:tblGrid>
                <w:tr>$properties${cell(first, "A")}${cell("", "B")}</w:tr>
                <w:tr>$properties${cell(second, if (merged) "" else "C")}${cell("", "D")}</w:tr>
                </w:tbl>"""
            val document = source.substringBefore("<w:body>") + "<w:body>$table<w:sectPr>" + source.substringAfter("<w:sectPr>")
            val input = PdfFixture.zip(parts + (path to document.toByteArray()))
            val result = NativeDocxReader(GenerationContext()) { _, _, _ -> "regular" }.read(input).blocks.single() as PdfTable
            assertEquals(listOf(!merged && !cantSplit, !merged && !cantSplit), result.rows.map { it.allowSplit })
            assertEquals(if (merged) 2 else 1, result.rows.first().cells.first().rowSpan)
        }
    }

    private fun cell(merge: String, text: String): String =
        """<w:tc><w:tcPr><w:tcW w:type="dxa" w:w="1800"/>$merge</w:tcPr>""" +
            """<w:p><w:pPr><w:spacing w:before="0" w:after="0" w:line="280" w:lineRule="exact"/></w:pPr>""" +
            (if (text.isEmpty()) "" else "<w:r><w:t>$text</w:t></w:r>") + "</w:p></w:tc>"
}
