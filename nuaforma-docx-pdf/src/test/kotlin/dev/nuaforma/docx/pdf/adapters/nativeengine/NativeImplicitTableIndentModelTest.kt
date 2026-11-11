package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.pdf.PdfTable
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeImplicitTableIndentModelTest {

    @Test
    fun `legacy absent indentation differs from declared zero while modern placement stays unchanged`() {
        for (mode in listOf("absent", "12", "14", "15")) for (kind in listOf("implicit", "explicit")) {
            val source = requireNotNull(javaClass.getResourceAsStream("/implicit-table-indent/$mode-$kind/a4/source.docx")).use { it.readBytes() }
            val table = NativeDocxReader(GenerationContext(), { _, _, _ -> "fixture" }).read(source).blocks.filterIsInstance<PdfTable>().single()
            val expected = if (mode == "15") .25f else if (kind == "implicit") 0f else -5f
            assertEquals(expected, table.leftIndent, .001f, "$mode $kind")
        }
    }

}
