package dev.nuaforma.docx.pdf.ownengine

import dev.nuaforma.pdf.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.*

class OwnEngineHebrewTest {
    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes() }
    private fun fixture() = resource("/own-engine-hebrew/source.docx")
    private fun read(bytes: ByteArray = fixture()) = OwnEngineDocxReader().read(bytes)
    private fun text(p: PdfParagraph) = p.runs.filterIsInstance<PdfTextRun>().joinToString("") { it.text }
    private fun fonts() = listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic").map { (id, name) ->
        PdfFont(id, resource("/own-engine-fonts/LiberationSans-$name.ttf"))
    }

    @Test fun `explicit source direction survives tables bands wrapping and adjacent Latin lists`() {
        val document = read()
        val paragraphs = document.blocks.filterIsInstance<PdfParagraph>()
        val expected = document.blocks.flatMap { block -> when (block) {
            is PdfParagraph -> listOf(block)
            is PdfTable -> block.rows.flatMap { row -> row.cells.flatMap { it.paragraphs } }
            else -> error("Unexpected proof block")
        } }.joinToString(" ") { text(it) }.replace(Regex("\\s+"), " ").trim()
        assertTrue(paragraphs.filter { p -> text(p).any { it in '\u0590'..'\u05ff' } }.all { it.format.textDirection == PdfTextDirection.RIGHT_TO_LEFT })
        assertTrue(paragraphs.filter { text(it).startsWith("L") }.all { it.format.textDirection == PdfTextDirection.LEFT_TO_RIGHT })
        for (sorted in listOf(false, true)) Loader.loadPDF(PdfEngine().render(document, fonts())).use { pdf ->
            assertEquals(2, pdf.numberOfPages)
            val all = PDFTextStripper().also { it.sortByPosition = sorted }.getText(pdf)
            for (id in listOf("TA", "TB", "LA", "LB", "LC")) assertEquals(1, Regex("\\b$id\\b").findAll(all).count(), id)
            for (value in listOf("שלום עולם.", "עלות 123.45", "כמות 67 יחידות.", "1. LA", "2. LB", "3. LC")) assertTrue(all.contains(value), value)
            for (page in 1..2) assertTrue(all.contains("עמוד $page"))
            val body = object : PDFTextStripper() {
                override fun processTextPosition(position: TextPosition) {
                    if (position.yDirAdj >= 90f && position.yDirAdj < 781.9f) super.processTextPosition(position)
                }
            }.also { it.sortByPosition = sorted }.getText(pdf).replace(Regex("\\s+"), " ").trim()
                .replace(Regex("\\b[123]\\. (?=L[ABC]\\b)"), "")
            assertEquals(expected, body)
        }
    }

    @Test fun `paragraph style direction inherits and direct false overrides it`() {
        val changed = mutate("word/styles.xml") { it.replaceFirst("<w:pPr>", "<w:pPr><w:bidi/>") }
        val ps = read(changed).blocks.filterIsInstance<PdfParagraph>()
        assertEquals(PdfTextDirection.RIGHT_TO_LEFT, ps.first().format.textDirection)
        assertEquals(PdfTextDirection.LEFT_TO_RIGHT, ps.single { text(it).startsWith("LA ") }.format.textDirection)
    }

    @Test fun `separate complex faces sizes weights and run overrides reject explicitly`() {
        val cases = listOf<(String) -> String>(
            { it.replace("w:cs=\"Liberation Sans\"", "w:cs=\"Noto Sans Hebrew\"") },
            { it.replace("<w:szCs w:val=\"22\"/>", "<w:szCs w:val=\"24\"/>") },
            { it.replace("<w:bCs/>", "<w:bCs w:val=\"0\"/>") },
            { it.replace("<w:iCs/>", "<w:iCs w:val=\"0\"/>") },
            { it.replace("<w:bCs w:val=\"0\"/>", "") },
            { it.replace("<w:szCs w:val=\"22\"/>", "") },
            { it.replace(" w:cs=\"Liberation Sans\"", "") },
            { it.replace("שלום עולם.", "שלום NuaForma עולם.") },
            { it.replaceFirst("<w:bidi/>", "<w:bidi><w:t>invalid</w:t></w:bidi>") },
            { it.replaceFirst("<w:bCs/>", "<w:bCs><w:b/></w:bCs>") },
            { it.replaceFirst("<w:rtl/>", "<w:rtl w:val=\"0\"/>") },
            { it.replaceFirst("<w:bidi/>", "<w:bidi w:val=\"maybe\"/>") }
        )
        for (change in cases) assertThrows(IllegalArgumentException::class.java) { read(mutate("word/document.xml", change)) }
    }

    @Test fun `forced complex-script overrides on fields and neutral-only runs reject`() {
        assertThrows(IllegalArgumentException::class.java) {
            read(mutate("word/footer1.xml") { it.replace("<w:rtl w:val=\"0\"/>", "<w:rtl/>") })
        }
        assertThrows(IllegalArgumentException::class.java) {
            read(mutate("word/document.xml") { it.replace("שלום עולם. המסמכים נבדקו והפרויקט מתקדם לפי התכנון.", "123.45") })
        }
    }

    private fun mutate(part: String, change: (String) -> String): ByteArray {
        val result = ByteArrayOutputStream()
        ZipOutputStream(result).use { output -> ZipInputStream(fixture().inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break; val bytes = input.readBytes()
                output.putNextEntry(ZipEntry(entry.name))
                output.write(if (entry.name == part) change(bytes.toString(Charsets.UTF_8)).also {
                    require(it != bytes.toString(Charsets.UTF_8))
                }.toByteArray(Charsets.UTF_8) else bytes)
                output.closeEntry()
            }
        } }
        return result.toByteArray()
    }
}
