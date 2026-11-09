package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.PdfFixture
import dev.nuaforma.pdf.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativePreferredTableModelTest {

    private fun source() = requireNotNull(javaClass.getResourceAsStream("/preferred-table-widths/a4/source.docx")).use { it.readBytes() }

    private fun read(bytes: ByteArray) = NativeDocxReader(GenerationContext(), { _, _, _ -> "fixture" }).read(bytes)

    private fun changed(transform: (String) -> String): ByteArray {
        val parts = PdfFixture.unzip(source())
        return PdfFixture.zip(parts.mapValues { (name, bytes) -> if (name == "word/document.xml") transform(bytes.decodeToString()).toByteArray() else bytes })
    }

    @Test
    fun `physical percentage and implicit automatic layout retain preferences separately from grid hints`() {
        val input = source()
        val before = input.copyOf()
        val model = read(input)
        val tables = model.blocks.filterIsInstance<PdfTable>()
        assertEquals(4, tables.size)
        assertTrue(tables.all { it.widthPolicy == PdfTableWidthPolicy.AUTO_FIT })
        assertTrue(tables.all { it.columnWidths == listOf(100f, 400f) })
        assertEquals(PdfPreferredWidth.points(320f), tables[0].preferredWidth)
        val reference = model.page.width - model.page.margins.left - model.page.margins.right + 10.8f
        assertEquals(reference * .8f, requireNotNull(tables[1].preferredWidth?.points), .001f)
        assertEquals(PdfPreferredWidth.percent(25f), tables[1].rows[0].cells[0].preferredWidth)
        assertEquals(PdfPreferredWidth.points(80f), tables[2].rows[0].cells[0].preferredWidth)
        assertEquals(2, tables[3].rows.last().cells.single().columnSpan)
        assertArrayEquals(before, input)
    }

    @Test
    fun `missing automatic dimensions use no preference and retain specified cell padding`() {
        val input = changed { it.replace(Regex("<w:(tblW|tcW)\\b[^>]*/>"), "")
            .replace(Regex("<w:tblBorders>.*?</w:tblBorders>"), "")
            .replace("<w:tblInd w:w=\"0\" w:type=\"dxa\"/>", "") }
        val tables = read(input).blocks.filterIsInstance<PdfTable>()
        assertTrue(tables.all { it.preferredWidth == null && it.border == null })
        assertTrue(tables.flatMap { it.rows }.flatMap { it.cells }.all { it.preferredWidth == null })
        assertEquals(PdfMargins(0f, 5.4f, 0f, 5.4f), tables.first().cellMargins)
    }

    @Test
    fun `unsupported units invalid percentages extra attributes and inconsistent fixed widths reject`() {
        val invalid = listOf<(String) -> String>(
            { it.replace("w:w=\"4000\" w:type=\"pct\"", "w:w=\"5001\" w:type=\"pct\"") },
            { it.replace("w:w=\"4000\" w:type=\"pct\"", "w:w=\"bad\" w:type=\"pct\"") },
            { it.replace("w:type=\"pct\"", "w:type=\"pixels\"") },
            { it.replace("<w:tblW ", "<w:tblW w:unknown=\"1\" ") },
            { it.replace("w:type=\"autofit\"", "w:type=\"fixed\"") })
        for (transform in invalid) assertThrows(NativeDocxReadException::class.java) { read(changed(transform)) }
    }

}
