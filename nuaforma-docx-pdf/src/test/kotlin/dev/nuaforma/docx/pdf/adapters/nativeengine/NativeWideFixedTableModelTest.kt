package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.PdfFixture
import dev.nuaforma.pdf.PdfTable
import dev.nuaforma.pdf.PdfTableWidthPolicy
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeWideFixedTableModelTest {

    @Test
    fun `wide body tables retain the declared fixed grid and only its needed margin allowance`() {
        for (mode in listOf("12", "15")) for (kind in listOf("omitted", "zero", "positive", "negative")) for (paper in listOf("a4", "letter")) {
            val source = requireNotNull(javaClass.getResourceAsStream("/wide-fixed-tables/$mode-$kind/$paper/source.docx")).use { it.readBytes() }
            val document = NativeDocxReader(GenerationContext(), { _, _, _ -> "fixture" }).read(source)
            val table = document.blocks.filterIsInstance<PdfTable>().single()
            val page = document.page
            val available = page.width - page.margins.left - page.margins.right
            assertEquals(PdfTableWidthPolicy.FIXED, table.widthPolicy)
            assertEquals(available + 23.3f, table.columnWidths.sum(), .001f)
            assertEquals(table.leftIndent + table.columnWidths.sum() - available, table.rightOverflow, .001f)
            assertTrue(table.rightOverflow > 0f)
            assertTrue(table.leftIndent + table.columnWidths.sum() < page.width - page.margins.left)
        }
    }

    @Test
    fun `fixed automatic-zero table width retains the explicit grid and physical cell widths`() {
        val table = readWithWidth("0", "auto")
        val explicit = readWithWidth("9492", "dxa")
        assertEquals(PdfTableWidthPolicy.FIXED, table.widthPolicy)
        assertEquals(explicit.columnWidths, table.columnWidths)
        assertEquals(explicit.rows.map { row -> row.cells.map { it.columnSpan } }, table.rows.map { row -> row.cells.map { it.columnSpan } })
        assertNull(table.preferredWidth)
    }

    @Test
    fun `fixed automatic width still requires zero and rejects a mismatched physical width`() {
        assertThrows(NativeDocxReadException::class.java) { readWithWidth("1", "auto") }
        assertThrows(NativeDocxReadException::class.java) { readWithWidth("0", "dxa") }
    }

    private fun readWithWidth(value: String, type: String): PdfTable {
        val source = requireNotNull(javaClass.getResourceAsStream("/wide-fixed-tables/15-zero/a4/source.docx")).use { it.readBytes() }
        val parts = PdfFixture.unzip(source)
        val xml = parts.getValue("word/document.xml").decodeToString()
        val width = Regex("""<w:tblW\b[^>]*/>""")
        check(width.findAll(xml).count() == 1)
        val changed = width.replace(xml, "<w:tblW w:w=\"$value\" w:type=\"$type\"/>")
        val candidate = PdfFixture.zip(parts + ("word/document.xml" to changed.toByteArray()))
        return NativeDocxReader(GenerationContext(), { _, _, _ -> "fixture" }).read(candidate).blocks.filterIsInstance<PdfTable>().single()
    }

}
