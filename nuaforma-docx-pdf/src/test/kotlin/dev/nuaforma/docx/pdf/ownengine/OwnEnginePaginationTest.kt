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

class OwnEnginePaginationTest {
    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes() }
    private fun fixture() = resource("/own-engine-pagination/source.docx")
    private fun fonts() = listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic").map { (id, name) ->
        PdfFont(id, resource("/own-engine-fonts/LiberationSans-$name.ttf"))
    }
    private fun text(p: PdfParagraph) = p.runs.filterIsInstance<PdfTextRun>().joinToString("") { it.text }

    @Test fun `source inheritance and page boundaries preserve all content without blank pages`() {
        val model = OwnEngineDocxReader().read(fixture())
        val paragraphs = model.blocks.filterIsInstance<PdfParagraph>()
        val heading = paragraphs.single { text(it) == "Gekoppelde hoofdkop" }
        assertTrue(heading.format.keepWithNext && heading.format.keepLines)
        assertTrue(paragraphs.single { text(it).startsWith("K01") }.format.keepLines)
        assertTrue(paragraphs.single { text(it).startsWith("O01") }.format.widowControl)
        assertTrue(paragraphs.first().format.pageBreakBefore)
        Loader.loadPDF(PdfEngine().render(model, fonts())).use { pdf ->
            assertEquals(10, pdf.numberOfPages)
            val pages = (1..10).map { n -> PDFTextStripper().apply { startPage = n; endPage = n }.getText(pdf) }
            assertTrue(pages.first().contains("F1-01"))
            assertTrue(pages[1].contains("Gekoppelde hoofdkop") && pages[1].contains("Gekoppelde tussenkop"))
            for ((marker, page) in listOf("G" to 2, "K" to 4, "O" to 6)) {
                for (line in 1..4) assertTrue(pages[page - 1].contains("${marker}0${line}documentcontrole"))
            }
            assertTrue(pages[6].contains("W01") && pages[6].contains("W02")); assertFalse(pages[6].contains("W03"))
            assertTrue(pages[7].contains("W03") && pages[7].contains("W04"))
            assertTrue(pages[8].contains("Kop met doorlopende vervolgalinea") && pages[8].contains("N01"))
            assertFalse(pages[8].contains("N02")); assertTrue(pages[9].contains("N02") && pages[9].contains("N04"))
            val all = pages.joinToString(" ")
            for ((case, count) in listOf(1 to 37, 2 to 37, 3 to 38, 4 to 36, 5 to 36)) {
                for (line in 1..count) assertEquals(1, Regex("F$case-%02d\\b".format(java.util.Locale.ROOT, line)).findAll(all).count())
            }
            for (marker in listOf("G", "K", "O", "W", "N")) for (line in 1..4) {
                assertEquals(1, Regex("${marker}0${line}documentcontrole").findAll(all).count())
            }
            for (page in 0..9) {
                assertTrue(pages[page].contains("Pagina ${page + 1}"))
                assertEquals(1, pdf.getPage(page).resources.xObjectNames.count())
            }
        }
    }

    @Test fun `explicit false overrides inherited keeps and omitted widow control uses Word default`() {
        val changed = mutate("word/document.xml") { it.replace("<w:pStyle w:val=\"PaginationHeading\"/>", "<w:pStyle w:val=\"PaginationHeading\"/><w:keepNext w:val=\"0\"/><w:keepLines w:val=\"false\"/>") }
        val heading = OwnEngineDocxReader().read(changed).blocks.filterIsInstance<PdfParagraph>().single { text(it) == "Gekoppelde hoofdkop" }
        assertFalse(heading.format.keepWithNext); assertFalse(heading.format.keepLines)
        val noDefault = mutate("word/styles.xml") { it.replace("<w:widowControl w:val=\"0\"/>", "") }
        val first = OwnEngineDocxReader().read(noDefault).blocks.filterIsInstance<PdfParagraph>().first()
        assertTrue(first.format.widowControl)
    }

    @Test fun `invalid pagination toggles and unqualified cell keeps are rejected`() {
        for (flag in listOf("keepNext", "keepLines", "widowControl", "pageBreakBefore")) {
            val invalid = mutate("word/document.xml") { it.replaceFirst("<w:pPr>", "<w:pPr><w:$flag w:val=\"invalid\"/>") }
            assertThrows(IllegalArgumentException::class.java) { OwnEngineDocxReader().read(invalid) }
        }
        val model = OwnEngineDocxReader().read(fixture())
        val p = model.blocks.filterIsInstance<PdfParagraph>().first { text(it).startsWith("K01") }
        val table = PdfTable(listOf(450f), listOf(PdfTableRow(listOf(PdfTableCell(listOf(p))))))
        assertEquals(PdfFailureCode.UNSUPPORTED_TEXT, assertThrows(PdfRenderException::class.java) {
            PdfEngine().render(PdfDocument(model.page, listOf(table), PdfFlow()), fonts())
        }.code)
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
