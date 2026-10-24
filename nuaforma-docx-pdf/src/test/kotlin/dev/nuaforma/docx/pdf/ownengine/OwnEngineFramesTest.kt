package dev.nuaforma.docx.pdf.ownengine

import dev.nuaforma.pdf.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class OwnEngineFramesTest {
    private fun bytes(path: String) = requireNotNull(javaClass.getResourceAsStream(path)).use { it.readBytes() }
    private fun model(kind: String) = OwnEngineDocxReader().read(bytes("/own-engine-frames/$kind/source.docx"))
    private fun render(document: PdfDocument) = PdfEngine().render(document, listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic").map { (id, face) -> PdfFont(id, bytes("/own-engine-fonts/LiberationSans-$face.ttf")) })

    @Test fun `unqualified VML rotation flipped DrawingML and vertical text directions reject explicitly`() {
        for ((kind, old, replacement) in listOf(Triple("frames", "rotation:0", "rotation:90"),
            Triple("rotated", "<a:xfrm rot=", "<a:xfrm flipH=\"1\" rot="), Triple("rotated", "vert=\"horz\"", "vert=\"vert\""))) {
            val output = ByteArrayOutputStream()
            ZipOutputStream(output).use { zip ->
                ZipInputStream(bytes("/own-engine-frames/$kind/source.docx").inputStream()).use { input ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        val value = input.readBytes()
                        zip.putNextEntry(ZipEntry(entry.name))
                        zip.write(if (entry.name == "word/document.xml") value.toString(Charsets.UTF_8).replace(old, replacement).toByteArray(Charsets.UTF_8) else value)
                        zip.closeEntry()
                    }
                }
            }
            assertThrows(IllegalArgumentException::class.java) { OwnEngineDocxReader().read(output.toByteArray()) }
        }
    }

    @Test fun `header and footer images and live numbers repeat without losing body lines`() {
        Loader.loadPDF(render(model("bands"))).use { pdf ->
            assertEquals(3, pdf.numberOfPages)
            val text = PDFTextStripper().getText(pdf)
            (1..82).forEach { assertEquals(1, Regex("\\bB%03d\\b".format(java.util.Locale.ROOT, it)).findAll(text).count()) }
            (0..2).forEach { index ->
                assertEquals(2, pdf.getPage(index).resources.xObjectNames.count())
                assertTrue(PDFTextStripper().apply { startPage = index + 1; endPage = index + 1 }.getText(pdf).contains("Pagina ${index + 1}"))
            }
        }
    }

    @Test fun `VML frames preserve padding alignment and paragraph anchored top bottom wrapping`() {
        val doc = model("frames")
        val boxes = doc.blocks.filterIsInstance<PdfTextBox>()
        assertEquals(3, boxes.size)
        assertEquals(PdfVerticalAlignment.CENTER, boxes[1].verticalAlignment)
        assertEquals(PdfBoxAnchor.PARAGRAPH, boxes[2].anchor)
        assertEquals(PdfBoxWrap.TOP_BOTTOM, boxes[2].wrap)
        Loader.loadPDF(render(doc)).use { pdf ->
            assertEquals(1, pdf.numberOfPages)
            val text = PDFTextStripper().getText(pdf)
            assertTrue(text.contains("Linksboven") && text.contains("Gecentreerd") && text.contains("Alineagebonden"))
            assertTrue(text.contains("Na het tekstvak"))
        }
    }

    @Test fun `DrawingML frames retain separate clockwise rotations and barcode images`() {
        val doc = model("rotated")
        assertEquals(listOf(90f, 270f, 90f, 270f, 45f), doc.blocks.filterIsInstance<PdfTextBox>().map { it.rotation })
        Loader.loadPDF(render(doc)).use { pdf ->
            assertEquals(1, pdf.numberOfPages)
            assertEquals(3, pdf.getPage(0).resources.xObjectNames.count())
            val text = StringBuilder()
            object : PDFTextStripper() {
                override fun processTextPosition(position: TextPosition) { text.append(position.unicode) }
            }.getText(pdf)
            assertTrue(text.contains("LINKS NEGENTIG GRADEN"))
            assertTrue(text.contains("RECHTS TWEEHONDERDZEVENTIG"))
        }
    }
}
