package dev.nuaforma.pdf

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PdfTableLayoutOptionsTest {
    private val style = PdfTextStyle("fixture", 10f)
    private val blank = PdfParagraph(listOf(PdfTextRun(" ", style)))
    private val text = PdfParagraph(listOf(PdfTextRun("Cell", style)))
    private val cell = PdfTableCell(listOf(text))
    private val row = PdfTableRow(listOf(cell))
    private val table = PdfTable(listOf(100f), listOf(row))

    private fun rejected(message: String, operation: () -> Unit) {
        assertEquals(message, assertThrows(IllegalArgumentException::class.java) { operation() }.message)
    }

    @Test
    fun `protected and unbroken widths are confined to a single qualified cell`() {
        val preferred = PdfPreferredWidth.points(80f)
        val physical = PdfTableCell(listOf(text), preferredWidth = preferred).withProtectedPreferredWidth(true)
        assertTrue(physical.protectPreferredWidth)
        assertSame(physical, physical.withProtectedPreferredWidth(true))
        assertFalse(physical.withProtectedPreferredWidth(false).protectPreferredWidth)
        for (candidate in listOf(cell, PdfTableCell(listOf(text), preferredWidth = PdfPreferredWidth.percent(60f)),
            PdfTableCell(listOf(text), columnSpan = 2, preferredWidth = preferred),
            PdfTableCell(listOf(text), rowSpan = 2, preferredWidth = preferred))) {
            rejected("Protected cell width requires a positive physical preference and a single grid cell") {
                candidate.withProtectedPreferredWidth(true)
            }
        }
        val unbroken = cell.withUnbrokenContent(true)
        assertSame(unbroken, unbroken.withUnbrokenContent(true))
        assertEquals(listOf(text), unbroken.printableBlocks)
        assertFalse(unbroken.withUnbrokenContent(false).keepContentUnbroken)
        for (candidate in listOf(PdfTableCell(listOf(text), columnSpan = 2), PdfTableCell(listOf(text), rowSpan = 2),
            PdfTableCell(listOf(table)))) {
            rejected("Unbroken automatic cell content requires unmerged paragraphs") { candidate.withUnbrokenContent(true) }
        }
    }

    @Test
    fun `omitted cell marks must contain only unstyled unlinked spaces`() {
        val cellWithMark = PdfTableCell(listOf(text, blank)).withOmittedTrailingEmptyParagraph(true)
        assertSame(cellWithMark, cellWithMark.withOmittedTrailingEmptyParagraph(true))
        assertEquals(listOf(text), cellWithMark.printableBlocks)
        assertEquals(listOf(text, blank), cellWithMark.withOmittedTrailingEmptyParagraph(false).printableBlocks)
        for (block in listOf(table, text, PdfParagraph(listOf(PdfTab(style))),
            PdfParagraph(listOf(PdfTextRun(" ", style.withBackground(PdfColor(1, 2, 3))))),
            PdfParagraph(listOf(PdfTextRun(" ", style.copy(underline = true)))),
            PdfParagraph(listOf(PdfTextRun(" ", style.withStrikethrough(true)))),
            blank.withBackground(PdfColor(1, 2, 3)), blank.copy(format = PdfParagraphFormat(pageBreakBefore = true)))) {
            rejected("Only a plain trailing empty table paragraph can be omitted") {
                PdfTableCell(listOf(text, block)).withOmittedTrailingEmptyParagraph(true)
            }
        }
    }

    @Test
    fun `row continuation and omitted columns preserve the physical grid constraints`() {
        assertSame(row, row.withOmittedColumns(0, 0))
        val omitted = row.withOmittedColumns(1, 2)
        assertEquals(1, omitted.omittedLeadingColumns)
        assertEquals(2, omitted.omittedTrailingColumns)
        for ((left, right) in listOf(-1 to 0, 16 to 0, 0 to -1, 0 to 16, 8 to 8)) {
            rejected("Invalid omitted table-column count") { row.withOmittedColumns(left, right) }
        }
        val empty = PdfTableRow(emptyList())
        for (width in listOf(Float.NaN, -1f, 7f)) {
            rejected("Invalid merged continuation border") { empty.withMergedCellContinuation(blank, width) }
        }
        rejected("Invalid merged continuation border") { empty.withMergedCellContinuation(null, 1f) }
        for (candidate in listOf(row, PdfTableRow(emptyList(), allowSplit = true))) {
            rejected("A merged row continuation must be blank and owned by its origin cell") {
                candidate.withMergedCellContinuation(blank, 0f)
            }
        }
        for (mark in listOf(text, PdfParagraph(listOf(PdfTab(style))), blank.withBackground(PdfColor(1, 2, 3)),
            blank.withEndDestinations(listOf(PdfDestination("mark"))))) {
            rejected("A merged row continuation must be blank and owned by its origin cell") {
                empty.withMergedCellContinuation(mark, 0f)
            }
        }
        val clearance = empty.withMergedCellContinuation(blank, 6f)
        val spanning = PdfTableRow(listOf(PdfTableCell(listOf(text), rowSpan = 2)))
        assertEquals(6f, PdfTable(listOf(100f), listOf(spanning, clearance)).rows.last().continuationTopBorder)
        for (widths in listOf(listOf(100f, 100f), listOf(100f))) {
            rejected("A measured vertical continuation requires one top-aligned cell spanning two rows") {
                PdfTable(widths, listOf(row, clearance))
            }
        }
    }

    @Test
    fun `text-relative positioning retains the fixed left-aligned model`() {
        assertSame(table, table.withTextRelativeTopOffset(null))
        val positioned = table.withTextRelativeTopOffset(12f)
        assertEquals(12f, positioned.textRelativeTopOffset)
        assertSame(positioned, positioned.withTextRelativeTopOffset(12f))
        for (height in listOf(Float.NaN, -1f, 14_401f)) {
            rejected("Invalid text-relative table position") { table.withTextRelativeTopOffset(height) }
        }
        rejected("Invalid text-relative table position") { positioned.withAlignment(PdfTableAlignment.CENTER) }
        rejected("Invalid text-relative table position") {
            PdfTable(listOf(100f), listOf(row), widthPolicy = PdfTableWidthPolicy.AUTO_FIT).withTextRelativeTopOffset(0f)
        }
    }
}
