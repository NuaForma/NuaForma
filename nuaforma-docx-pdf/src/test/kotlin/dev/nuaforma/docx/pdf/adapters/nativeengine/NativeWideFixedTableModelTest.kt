package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
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

}
