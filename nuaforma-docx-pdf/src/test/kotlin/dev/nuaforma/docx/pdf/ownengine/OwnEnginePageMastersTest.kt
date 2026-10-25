package dev.nuaforma.docx.pdf.ownengine

import dev.nuaforma.pdf.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.util.zip.*

class OwnEnginePageMastersTest {
    private fun source(kind: String) = requireNotNull(javaClass.getResourceAsStream("/own-engine-page-masters/$kind/source.docx")).use { it.readBytes() }
    private fun fonts() = listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic").map { (id, name) ->
        PdfFont(id, requireNotNull(javaClass.getResourceAsStream("/own-engine-fonts/LiberationSans-$name.ttf")).use { it.readBytes() })
    }

    @ParameterizedTest @ValueSource(strings = ["variants", "blank"])
    fun `first even odd and inherited source bands preserve actual PDF content and images`(kind: String) {
        val model = OwnEngineDocxReader().read(source(kind))
        val expected = if (kind == "variants") listOf("FIRST","EVEN","ODD","FIRST","ODD","EVEN","ODD","FIRST","ODD")
            else listOf(null,null,"ODD","FIRST","ODD","EVEN","ODD","EVEN","ODD")
        if (kind == "blank") {
            assertNull(model.pageSequences[0].flow!!.firstPageBands!!.header)
            assertNull(model.pageSequences[0].flow!!.evenPageBands!!.footer)
            assertNull(model.pageSequences[2].flow!!.firstPageBands)
        } else assertTrue(model.pageSequences.all { it.flow?.firstPageBands?.header != null })
        Loader.loadPDF(PdfEngine().render(model, fonts())).use { pdf ->
            assertEquals(9, pdf.numberOfPages)
            for ((i,label) in expected.withIndex()) {
                val text = PDFTextStripper().apply { startPage = i + 1; endPage = i + 1 }.getText(pdf)
                for (value in listOf("FIRST","EVEN","ODD")) {
                    assertEquals(value == label, text.contains("H-$value")); assertEquals(value == label, text.contains("F-$value"))
                }
                assertEquals(if (label == null) emptyList() else listOf((i+1).toString()), Regex("\\bPagina\\s+(\\d+)\\b").findAll(text).map { it.groupValues[1] }.toList())
                val page = pdf.getPage(i)
                assertEquals(label != null, page.resources.xObjectNames.any { page.resources.getXObject(it) is PDImageXObject })
                assertTrue(page.resources.fontNames.all { page.resources.getFont(it).isEmbedded })
            }
            val text = PDFTextStripper().getText(pdf)
            for (n in 1..28) assertEquals(3, Regex("\\bW${n.toString().padStart(2,'0')}\\b").findAll(text).count())
        }
    }

    @Test fun `explicit false switches ignore retained variant references without losing default inheritance`() {
        val bytes = change { name, xml -> when(name) {
            "word/document.xml" -> xml.replace("w:val=\"true\"", "w:val=\"false\"")
            "word/settings.xml" -> xml.replace("<w:evenAndOddHeaders/>", "<w:evenAndOddHeaders w:val=\"false\"/>")
            else -> xml
        } }
        val model = OwnEngineDocxReader().read(bytes)
        assertTrue(model.pageSequences.all { it.flow!!.firstPageBands == null && it.flow!!.evenPageBands == null })
        Loader.loadPDF(PdfEngine().render(model, fonts())).use { pdf ->
            val text = PDFTextStripper().getText(pdf)
            assertEquals(9, Regex("H-ODD").findAll(text).count()); assertEquals(9, Regex("F-ODD").findAll(text).count())
            assertFalse(text.contains("H-FIRST") || text.contains("H-EVEN"))
        }
    }

    @Test fun `duplicate types invalid toggles wrong band relationships and traversal targets reject`() {
        val mutations: List<(String,String)->String> = listOf(
            { name, xml -> if (name == "word/document.xml") xml.replace("w:type=\"first\"", "w:type=\"default\"") else xml },
            { name, xml -> if (name == "word/document.xml") xml.replace("w:val=\"true\"", "w:val=\"sometimes\"") else xml },
            { name, xml -> if (name == "word/document.xml") xml.replace("r:id=\"header2\"", "r:id=\"footer2\"") else xml },
            { name, xml -> if (name == "word/_rels/document.xml.rels") xml.replace("Target=\"header2.xml\"", "Target=\"../header2.xml\"") else xml },
        )
        for (mutation in mutations) assertThrows(IllegalArgumentException::class.java) { OwnEngineDocxReader().read(change(mutation)) }
    }

    private fun change(transform: (String,String)->String): ByteArray {
        val output = ByteArrayOutputStream(); var changed = false
        ZipOutputStream(output).use { target -> ZipInputStream(source("variants").inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break; val bytes = input.readBytes(); target.putNextEntry(ZipEntry(entry.name))
                val value = if (entry.name.endsWith(".xml") || entry.name.endsWith(".rels")) transform(entry.name,bytes.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8) else bytes
                changed = changed || !value.contentEquals(bytes); target.write(value); target.closeEntry()
            }
        } }
        require(changed); return output.toByteArray()
    }
}
