package dev.nuaforma.docx.pdf.ownengine

import dev.nuaforma.pdf.*
import dev.nuaforma.contracts.GenerationException
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class OwnEngineDocxReaderTest {
    private fun resource(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes() }
    private fun fixture() = resource("/own-engine/source.docx")
    private fun fonts() = listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic").map { (id, face) ->
        PdfFont(id, resource("/own-engine-fonts/LiberationSans-$face.ttf"))
    }

    @Test fun `real DOCX translates geometry inherited styles image and searchable text`() {
        val model = OwnEngineDocxReader().read(fixture())
        assertEquals(595.3f, model.page.width, 0.01f)
        assertEquals(841.9f, model.page.height, 0.01f)
        assertEquals(56.7f, model.page.margins.left, 0.01f)
        val paragraphs = model.blocks.filterIsInstance<PdfParagraph>()
        assertEquals(11, paragraphs.size)
        assertEquals(54f, (paragraphs[0].runs.single() as PdfImageRun).image.width)
        val title = paragraphs[1].runs.single() as PdfTextRun
        assertEquals("bold", title.style.fontId)
        assertEquals(18f, title.style.size)
        assertEquals(PdfAlignment.RIGHT, paragraphs[2].format.alignment)
        assertEquals(PdfAlignment.CENTER, paragraphs[7].format.alignment)
        assertEquals(24f, paragraphs[8].format.leftIndent)
        assertEquals(12f, paragraphs[8].format.firstLineIndent)
        assertEquals(12f, paragraphs[8].format.rightIndent)
        assertTrue(paragraphs[6].runs.filterIsInstance<PdfTextRun>().any { it.style.fontId == "italic" })
        Loader.loadPDF(PdfEngine().render(model, fonts())).use { pdf ->
            assertEquals(1, pdf.numberOfPages)
            val text = PDFTextStripper().getText(pdf).replace(Regex("\\s+"), " ")
            for (expected in listOf("Bevestiging van uw aanvraag", "NF-2026-0057", "€ 1.234,56", "café", "beëindiging", "coördinatie", "Team NuaForma")) {
                assertTrue(text.contains(expected), expected)
            }
            assertEquals(3, pdf.getPage(0).resources.fontNames.count())
            assertEquals(1, pdf.getPage(0).resources.xObjectNames.count())
            val lines = mutableMapOf<String, List<TextPosition>>()
            val positions = object : PDFTextStripper() {
                override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                    lines[text.trim()] = textPositions.toList()
                    super.writeString(text, textPositions)
                }
            }
            positions.getText(pdf)
            val date = requireNotNull(lines["24 oktober 2026"])
            assertEquals(model.page.width - model.page.margins.right, date.maxOf { it.xDirAdj + it.widthDirAdj }, 0.05f)
            val centered = requireNotNull(lines["Wij houden u op de hoogte"])
            assertEquals(model.page.width / 2, (centered.minOf { it.xDirAdj } + centered.maxOf { it.xDirAdj + it.widthDirAdj }) / 2, 0.05f)
            val indented = lines.entries.first { it.key.startsWith("Deze ingesprongen") }.value
            assertEquals(92.7f, indented.first().xDirAdj, 0.05f)
        }
    }

    @Test fun `direct false overrides inherited formatting and repeated positive styles stay enabled`() {
        val changed = mutate("word/document.xml") { it.replace("<w:r><w:rPr></w:rPr><w:t xml:space=\"preserve\">Bevestiging", "<w:r><w:rPr><w:b w:val=\"0\"/></w:rPr><w:t xml:space=\"preserve\">Bevestiging") }
        val title = (OwnEngineDocxReader().read(changed).blocks[1] as PdfParagraph).runs.single() as PdfTextRun
        assertEquals("regular", title.style.fontId)
        val toggled = mutate("word/styles.xml") { it.replace("<w:pPr><w:spacing w:before=\"0\" w:after=\"240\"", "<w:rPr><w:b/></w:rPr><w:pPr><w:spacing w:before=\"0\" w:after=\"240\"") }
        val toggledTitle = (OwnEngineDocxReader().read(toggled).blocks[1] as PdfParagraph).runs.single() as PdfTextRun
        assertEquals("bold", toggledTitle.style.fontId)
    }

    @Test fun `unsupported document features and external relationships are rejected explicitly`() {
        val cases = listOf(
            "word/document.xml" to { xml: String -> xml.replace("<w:body>", "<w:background w:color=\"FF0000\"/><w:body>") },
            "word/document.xml" to { xml: String -> xml.replace("<w:body>", "<w:body><w:tbl/>") },
            "word/document.xml" to { xml: String -> xml.replace("<w:pPr>", "<w:pPr><w:numPr/>") },
            "word/document.xml" to { xml: String -> xml.replace("<wp:inline ", "<wp:anchor ").replace("</wp:inline>", "</wp:anchor>") },
            "word/document.xml" to { xml: String -> xml.replace("<w:rPr>", "<w:rPr><w:u w:val=\"double\"/>") },
            "word/_rels/document.xml.rels" to { xml: String -> xml.replace("Target=\"media/logo.png\"", "Target=\"https://invalid.example/logo.png\" TargetMode=\"External\"") },
            "word/styles.xml" to { xml: String -> xml.replace("<w:basedOn w:val=\"Normal\"/>", "<w:basedOn w:val=\"Title\"/>") },
        )
        for ((part, change) in cases) assertThrows(IllegalArgumentException::class.java) { OwnEngineDocxReader().read(mutate(part, change)) }
    }

    @Test fun `malformed XML and excessive archive content fail before rendering`() {
        assertThrows(GenerationException::class.java) { OwnEngineDocxReader().read(mutate("word/document.xml") { "<!DOCTYPE a [<!ENTITY x SYSTEM 'file:///never-read'>]><a>&x;</a>" }) }
        assertThrows(GenerationException::class.java) { OwnEngineDocxReader().read(mutate("word/document.xml") { " ".repeat(4_194_305) }) }
        assertThrows(IllegalArgumentException::class.java) { OwnEngineDocxReader().read(byteArrayOf(1, 2, 3)) }
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
