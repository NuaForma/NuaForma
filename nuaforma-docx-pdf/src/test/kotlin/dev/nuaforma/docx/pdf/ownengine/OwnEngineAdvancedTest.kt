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

class OwnEngineAdvancedTest {
    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes() }
    private fun model(kind: String) = OwnEngineDocxReader().read(resource("/own-engine-advanced/$kind/source.docx"))
    private fun pages(document: PdfDocument): List<String> {
        val fonts = listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic").map { (id, name) ->
            PdfFont(id, resource("/own-engine-fonts/LiberationSans-$name.ttf"))
        }
        return Loader.loadPDF(PdfEngine().render(document, fonts)).use { pdf ->
            (1..pdf.numberOfPages).map { number -> PDFTextStripper().apply { startPage = number; endPage = number }.getText(pdf) }
        }
    }
    private fun once(text: String, marker: String) = assertEquals(1, Regex("\\b${Regex.escape(marker)}\\b").findAll(text).count(), marker)

    @Test fun `vertical continuation without origin is rejected`() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            ZipInputStream(resource("/own-engine-advanced/merged/source.docx").inputStream()).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val bytes = input.readBytes()
                    zip.putNextEntry(ZipEntry(entry.name))
                    zip.write(if (entry.name == "word/document.xml") bytes.toString(Charsets.UTF_8)
                        .replaceFirst("<w:vMerge w:val=\"restart\"/>", "<w:vMerge/>").toByteArray(Charsets.UTF_8) else bytes)
                    zip.closeEntry()
                }
            }
        }
        assertThrows(IllegalArgumentException::class.java) { OwnEngineDocxReader().read(output.toByteArray()) }
    }

    @Test fun `merged grid retains origin labels once across pages and repeats both header rows`() {
        val document = model("merged")
        val table = document.blocks.filterIsInstance<PdfTable>().single()
        assertEquals(2, table.repeatedHeaderRows)
        assertEquals(2, table.rows.first().cells.first().columnSpan)
        assertEquals(8, table.rows[2].cells.first().rowSpan)
        val pages = pages(document)
        assertEquals(2, pages.size)
        val text = pages.joinToString(" ")
        (1..32).forEach { once(text, "M%02d".format(java.util.Locale.ROOT, it)) }
        (1..4).forEach { once(text, "Fase $it") }
        assertTrue(pages.all { it.contains("Indeling") && it.contains("Uitvoering") && it.contains("Categorie") })
    }

    @Test fun `nested tables contribute parent height and preserve every inner row`() {
        val document = model("nested")
        val table = document.blocks.filterIsInstance<PdfTable>().single()
        assertEquals(10, table.rows.drop(1).count { it.cells[1].blocks.any { block -> block is PdfTable } })
        val pages = pages(document)
        assertEquals(2, pages.size)
        val text = pages.joinToString(" ")
        (1..10).forEach { i -> (1..3).forEach { j -> once(text, "N%02d-%d".format(java.util.Locale.ROOT, i, j)) } }
        assertTrue(pages.all { it.contains("Controle en toelichting") })
    }

    @Test fun `split and oversized atomic rows retain every source paragraph without duplication`() {
        val document = model("split")
        val table = document.blocks.filterIsInstance<PdfTable>().single()
        assertTrue(table.rows[2].allowSplit)
        assertFalse(table.rows[3].allowSplit)
        assertTrue(table.rows[4].splitOversized)
        val pages = pages(document)
        val text = pages.joinToString(" ")
        listOf("A01" to 2, "S01" to 76, "K01" to 28, "K02" to 76).forEach { (code, count) ->
            (1..count).forEach { once(text, "%s-%03d".format(java.util.Locale.ROOT, code, it)) }
        }
        assertTrue(pages.count { it.contains("S01-") } >= 2)
        assertEquals(1, pages.count { it.contains("K01-") })
        assertFalse(pages.first { it.contains("K01-") }.contains("S01-"))
        assertTrue(pages.count { it.contains("K02-") } >= 2)
        assertTrue(pages.all { it.contains("Uitgevoerde controles") })
        assertTrue(pages.last().contains("Einde van het controleoverzicht."))
    }
}
