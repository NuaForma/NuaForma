package dev.nuaforma.docx.pdf.ownengine

import dev.nuaforma.pdf.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.*

class OwnEngineSectionsTest {
    private fun source() = requireNotNull(javaClass.getResourceAsStream("/own-engine-sections/source.docx")).use { it.readBytes() }
    private fun fonts() = listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic").map { (id, name) ->
        PdfFont(id, requireNotNull(javaClass.getResourceAsStream("/own-engine-fonts/LiberationSans-$name.ttf")).use { it.readBytes() })
    }

    @Test fun `source section scopes inherit bands and preserve physical geometry and global page numbers`() {
        val model = OwnEngineDocxReader().read(source())
        assertEquals(listOf(595.3f to 841.9f, 792f to 612f, 841.9f to 1190.55f), model.pageSequences.map { it.page.width to it.page.height })
        for (sequence in model.pageSequences) {
            assertNotNull(sequence.flow?.header); assertNotNull(sequence.flow?.footer)
        }
        Loader.loadPDF(PdfEngine().render(model, fonts())).use { pdf ->
            assertTrue(pdf.numberOfPages in 6..16)
            val widths = pdf.pages.map { it.mediaBox.width }
            val expected = listOf(595.3f, 792f, 841.9f)
            assertEquals(expected, widths.filterIndexed { i, width -> i == 0 || widths[i - 1] != width })
            for (i in 1..pdf.numberOfPages) {
                val page = pdf.getPage(i - 1)
                assertEquals(0, page.rotation)
                assertTrue(page.resources.xObjectNames.any { page.resources.getXObject(it) is PDImageXObject })
                assertTrue(page.resources.fontNames.all { page.resources.getFont(it).isEmbedded })
                val text = PDFTextStripper().apply { startPage = i; endPage = i }.getText(pdf)
                assertTrue(text.contains("Pagina $i"))
            }
            val text = PDFTextStripper().getText(pdf)
            for (n in 1..28) assertEquals(3, Regex("\\bW${n.toString().padStart(2,'0')}\\b").findAll(text).count())
            for (label in listOf("A A4 staand", "B Letter liggend", "C A3 staand")) {
                assertEquals(1, Regex("Deel $label").findAll(text).count())
                assertEquals(1, Regex("Einde deel $label").findAll(text).count())
            }
            assertEquals(3, Regex("Київ і Україна").findAll(text).count())
        }
    }

    @Test fun `default next-page transitions work while unqualified section properties reject`() {
        val defaults = OwnEngineDocxReader().read(change { it.replace(Regex("<w:type[^>]*/>"), "") })
        assertEquals(3, defaults.pageSequences.size)
        assertTrue(defaults.pageSequences.all { it.flow?.header != null && it.flow?.footer != null })
        for (kind in listOf("continuous", "oddPage", "evenPage", "nextColumn")) assertThrows(IllegalArgumentException::class.java) {
            OwnEngineDocxReader().read(change { it.replace("w:val=\"nextPage\"", "w:val=\"$kind\"") })
        }
        assertThrows(IllegalArgumentException::class.java) {
            OwnEngineDocxReader().read(change { it.replace(Regex("<w:pgSz[^>]*/>"), "") })
        }
        assertThrows(IllegalArgumentException::class.java) {
            OwnEngineDocxReader().read(change { it.replace("<w:sectPr>", "<w:sectPr><w:cols w:num=\"2\"/>") })
        }
    }

    private fun change(transform: (String) -> String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { target -> ZipInputStream(source().inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val bytes = input.readBytes(); target.putNextEntry(ZipEntry(entry.name))
                target.write(if (entry.name == "word/document.xml") transform(bytes.toString(Charsets.UTF_8)).also {
                    require(it != bytes.toString(Charsets.UTF_8))
                }.toByteArray(Charsets.UTF_8) else bytes)
                target.closeEntry()
            }
        } }
        return output.toByteArray()
    }
}
