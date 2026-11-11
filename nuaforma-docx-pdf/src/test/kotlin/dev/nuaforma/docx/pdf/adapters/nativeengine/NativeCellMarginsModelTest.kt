package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.pdf.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeCellMarginsModelTest {

    @Test
    fun `Word edge inheritance row maxima and border minimums become neutral physical margins`() {
        val source = requireNotNull(javaClass.getResourceAsStream("/cell-margins/a4/source.docx")).use { it.readBytes() }
        val before = source.copyOf()
        val tables = NativeDocxReader(GenerationContext(), { _, _, _ -> "fixture" }).read(source).blocks.filterIsInstance<PdfTable>()
        assertEquals(2, tables.size)
        // Independent Word COM padding readback and exported glyph positions are retained with these fixtures.
        val expected = listOf(
            listOf(PdfMargins(4f, 5f, 7.1f, 5f), PdfMargins(4f, 5f, 7.1f, 5f),
                PdfMargins(4f, .25f, 4f, 12f), PdfMargins(4f, 5f, 4f, 5f),
                PdfMargins(10f, 5f, 4f, .25f), PdfMargins(10f, 15f, 4f, 15f), PdfMargins(4f, 5f, 7.1f, 10f)),
            listOf(PdfMargins(2f, 5f, 7.1f, 10f), PdfMargins(2f, 5f, 7.1f, 10f),
                PdfMargins(2f, .25f, 4f, 12f), PdfMargins(2f, 5f, 4f, 10f),
                PdfMargins(10f, 5f, 4f, .25f), PdfMargins(10f, 15f, 4f, 15f), PdfMargins(2f, 5f, 7.1f, 10f)))
        for ((index, table) in tables.withIndex()) {
            assertEquals(expected[index], table.rows.flatMap { it.cells }.map { it.margins ?: table.cellMargins })
        }
        assertArrayEquals(before, source)
    }

}
