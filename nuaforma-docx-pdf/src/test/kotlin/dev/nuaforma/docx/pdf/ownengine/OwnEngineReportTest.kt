package dev.nuaforma.docx.pdf.ownengine

import dev.nuaforma.pdf.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class OwnEngineReportTest {
    private fun resource(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes() }
    private fun fixture() = resource("/own-engine-report/source.docx")
    private fun fonts() = listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic").map { (id, name) ->
        PdfFont(id, resource("/own-engine-fonts/LiberationSans-$name.ttf"))
    }

    @Test fun `report retains all rows repeating headers page bands and automatic page numbers`() {
        val model = OwnEngineDocxReader().read(fixture())
        val table = model.blocks.filterIsInstance<PdfTable>().single()
        assertEquals(29, table.rows.size)
        assertEquals(4, table.columnWidths.size)
        assertEquals(1, table.repeatedHeaderRows)
        assertEquals(0.5f, requireNotNull(table.border).width)
        val flow = requireNotNull(model.flow)
        assertNotNull(flow.header)
        assertNotNull(flow.footer)
        Loader.loadPDF(PdfEngine().render(model, fonts())).use { pdf ->
            assertEquals(3, pdf.numberOfPages)
            val text = (1..3).map { number -> PDFTextStripper().apply { startPage = number; endPage = number }.getText(pdf).replace(Regex("\\s+"), " ") }
            for (number in 1..3) {
                assertTrue(text[number - 1].contains("NuaForma | Voortgangsrapport"))
                assertTrue(text[number - 1].contains("NF-2026-0057 | Pagina $number"))
                assertEquals(1, pdf.getPage(number - 1).resources.xObjectNames.count())
                assertTrue(pdf.getPage(number - 1).resources.fontNames.all { pdf.getPage(number - 1).resources.getFont(it).isEmbedded })
            }
            for (index in 1..28) {
                val id = "W%02d".format(java.util.Locale.ROOT, index)
                assertEquals(1, Regex("\\b$id\\b").findAll(text.joinToString(" ")).count(), id)
            }
            assertTrue(text[0].contains("W12")); assertFalse(text[0].contains("W13"))
            assertTrue(text[1].contains("W13") && text[1].contains("W28"))
            assertTrue(text.take(2).all { it.contains("Code") && it.contains("Werkzaamheden") && it.contains("Aantal") && it.contains("Bedrag") })
            assertTrue(text[2].contains("Besluit"))
            val images = (0..2).map { pdf.getPage(it).resources.getXObject(pdf.getPage(it).resources.xObjectNames.single()).cosObject }
            assertSame(images[0], images[1]); assertSame(images[1], images[2])
        }
    }

    @Test fun `inconsistent spans invalid toggles invalid band types and unsafe fields fail explicitly`() {
        for ((part, change) in listOf(
            "word/document.xml" to { value: String -> value.replace("<w:tcPr>", "<w:tcPr><w:gridSpan w:val=\"2\"/>") },
            "word/document.xml" to { value: String -> value.replace("<w:cantSplit/>", "<w:cantSplit w:val=\"invalid\"/>") },
            "word/document.xml" to { value: String -> value.replace("w:type=\"default\"", "w:type=\"invalid\"") },
            "word/footer1.xml" to { value: String -> value.replace(" PAGE ", " INCLUDETEXT https://invalid.example/ ") },
        )) assertThrows(IllegalArgumentException::class.java) { OwnEngineDocxReader().read(mutate(part, change)) }
    }

    private fun mutate(part: String, change: (String) -> String): ByteArray {
        val result = ByteArrayOutputStream()
        ZipOutputStream(result).use { output ->
            ZipInputStream(fixture().inputStream()).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val bytes = input.readBytes()
                    output.putNextEntry(ZipEntry(entry.name))
                    output.write(if (entry.name == part) change(bytes.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8) else bytes)
                    output.closeEntry()
                }
            }
        }
        return result.toByteArray()
    }
}
