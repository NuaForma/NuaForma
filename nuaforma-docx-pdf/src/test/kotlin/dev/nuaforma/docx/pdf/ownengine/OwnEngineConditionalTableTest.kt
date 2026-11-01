package dev.nuaforma.docx.pdf.ownengine

import dev.nuaforma.pdf.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OwnEngineConditionalTableTest {
    private val cases = listOf("a4", "letter", "default-bands", "horizontal", "grouped-headers", "header-bands", "header-grouped",
        "legacy-precedence", "normal-legacy", "normal-override", "word12-legacy", "word12-override", "word12-header-bands", "word12-header-grouped")

    private fun table(name: String) = OwnEngineDocxReader().read(WordLayoutOracle.resource("/conditional-tables/$name/source.docx"))
        .blocks.filterIsInstance<PdfTable>().single()

    private fun face(table: PdfTable, row: Int, column: Int) = table.rows[row].cells[column].paragraphs.first().runs.filterIsInstance<PdfTextRun>().first().style

    @Test fun `corners rows columns bands and direct overrides resolve to neutral cell styles`() {
        val table = table("a4")
        assertEquals("bolditalic", face(table, 0, 0).fontId)
        assertEquals(PdfColor(0, 68, 0), face(table, 0, 0).color)
        assertEquals(PdfColor(0, 0, 204), face(table, 1, 1).color)
        assertEquals(PdfColor(17, 17, 102), face(table, 2, 1).color)
        assertEquals(PdfColor(34, 85, 136), face(table, 2, 2).color)
        assertEquals(PdfColor(17, 34, 51), face(table, 3, 2).color)
        assertEquals(PdfColor(255, 238, 136), table.rows[2].cells[2].background)
        assertEquals(2, table.repeatedHeaderRows)
        val horizontal = table("horizontal")
        assertEquals(PdfColor(17, 102, 17), face(horizontal, 2, 1).color)
        assertEquals(PdfColor(17, 102, 17), face(horizontal, 3, 1).color)
        assertEquals(PdfColor(102, 17, 17), face(horizontal, 4, 1).color)
        assertEquals(PdfColor(51, 51, 51), face(table("default-bands"), 2, 1).color)
    }

    @Test fun `legacy Normal style exceptions are limited to size eleven twelve and left justification`() {
        assertEquals(11f, face(table("word12-legacy"), 0, 1).size)
        assertEquals(12f, face(table("word12-legacy"), 2, 1).size)
        assertEquals(12f, face(table("word12-override"), 0, 1).size)
        assertEquals(12f, face(table("normal-legacy"), 0, 1).size)
        assertEquals(PdfAlignment.CENTER, table("word12-legacy").rows[0].cells[1].paragraphs.first().format.alignment)
        assertEquals(PdfAlignment.LEFT, table("word12-override").rows[0].cells[1].paragraphs.first().format.alignment)
    }

    @Test fun `fourteen real Word exports verify conditional cell text layout colors faces and fills`() {
        for (name in cases) WordLayoutOracle.qualify("conditional-tables", name, 1, Regex("^(C[0-5][0-3])\\b"), tableFills = true)
    }

}
