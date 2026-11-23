package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.placements
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PdfOmittedTableGridTest {
    private val paragraph = PdfParagraph(listOf(PdfTextRun("Cell", PdfTextStyle("Body", 10f))))
    private fun cell(rows: Int = 1) = PdfTableCell(listOf(paragraph), rowSpan = rows)

    @Test
    fun `edge slots retain a real grid position and leave internal row spans intact`() {
        val first = PdfTableRow(listOf(cell(2), cell())).withOmittedColumns(1, 1)
        val second = PdfTableRow(listOf(cell(), cell(), cell()))
        val table = PdfTable(listOf(45f, 60f, 75f, 90f), listOf(first, second))
        assertEquals(listOf(0 to 1, 0 to 2, 1 to 0, 1 to 2, 1 to 3), table.placements().map { it.row to it.column })
        assertThrows(UnsupportedOperationException::class.java) { (first.cells as MutableList).clear() }
    }

    @Test
    fun `a row span cannot enter an omitted slot or leave an uncovered internal slot`() {
        val first = PdfTableRow(listOf(cell(2), cell())).withOmittedColumns(1, 1)
        val conflict = PdfTableRow(listOf(cell(), cell())).withOmittedColumns(2, 0)
        val uncovered = PdfTableRow(listOf(cell(), cell()))
        val widths = listOf(45f, 60f, 75f, 90f)
        assertThrows(IllegalArgumentException::class.java) { PdfTable(widths, listOf(first, conflict)) }
        assertThrows(IllegalArgumentException::class.java) { PdfTable(widths, listOf(first, uncovered)) }
        assertThrows(IllegalArgumentException::class.java) {
            PdfTable(widths, listOf(first, PdfTableRow(listOf(cell(), cell(), cell()))), widthPolicy = PdfTableWidthPolicy.AUTO_FIT)
        }
    }
}
