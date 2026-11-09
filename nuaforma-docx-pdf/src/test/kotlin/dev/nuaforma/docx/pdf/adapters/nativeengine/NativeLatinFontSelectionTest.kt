package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.PdfFixture
import dev.nuaforma.pdf.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeLatinFontSelectionTest {

    private fun source() = requireNotNull(javaClass.getResourceAsStream("/mixed-latin-fonts/a4/source.docx")).use { it.readBytes() }

    private fun read(bytes: ByteArray) = NativeDocxReader(GenerationContext(), { family, bold, italic ->
        val prefix = when (family) { "Liberation Sans" -> "sans"; "Noto Serif" -> "serif"; else -> error("Unexpected fixture family") }
        "$prefix-$bold-$italic"
    }).read(bytes)

    private fun text(paragraph: PdfParagraph) = paragraph.runs.filterIsInstance<PdfTextRun>().joinToString("") { it.text }

    @Test
    fun `role inheritance and theme precedence preserve glyph fonts and one navigation destination`() {
        val source = source()
        val original = source.copyOf()
        val model = read(source)
        val paragraphs = model.blocks.filterIsInstance<PdfParagraph>()
        val expected = mapOf("R01" to ("serif" to "sans"), "R02" to ("sans" to "serif"),
            "R03" to ("serif" to "serif"), "R04" to ("serif" to "serif"), "R05" to ("sans" to "sans"),
            "R06" to ("serif" to "sans"), "R07" to ("serif" to "sans"), "R08" to ("sans" to "serif"),
            "R09" to ("sans" to "serif"), "R10" to ("sans" to "serif"), "R11" to ("sans" to "serif"), "R12" to ("sans" to "serif"))
        for ((marker, roles) in expected) {
            val paragraph = paragraphs.single { text(it).startsWith(marker) }
            for (run in paragraph.runs.filterIsInstance<PdfTextRun>()) {
                run.text.codePoints().forEach { point ->
                    assertEquals((if (point < 128) roles.first else roles.second) + "-false-false", run.style.fontId, "$marker U+${point.toString(16)}")
                }
            }
        }
        val bookmark = paragraphs.single { text(it).startsWith("R09") }.runs.filterIsInstance<PdfTextRun>()
        assertEquals(1, bookmark.sumOf { it.navigation?.destinations?.size ?: 0 })
        val linked = paragraphs.single { text(it).startsWith("R10") }.runs.filterIsInstance<PdfTextRun>()
        assertTrue(linked.all { it.navigation?.link is PdfLink.Internal && it.style.underline })
        assertEquals(1, linked.map { it.navigation?.logicalId }.distinct().size)
        assertTrue(text(paragraphs.single { text(it).startsWith("R11") }).contains("Property É11 café."))
        val table = model.blocks.filterIsInstance<PdfTable>().single()
        assertEquals(6, table.rows.sumOf { it.cells.size })
        for (cell in table.rows.flatMap { it.cells }) for (paragraph in cell.blocks.filterIsInstance<PdfParagraph>()) {
            for (run in paragraph.runs.filterIsInstance<PdfTextRun>()) run.text.codePoints().forEach { point ->
                assertEquals((if (point < 128) "sans" else "serif") + "-false-false", run.style.fontId)
            }
        }
        assertArrayEquals(original, source)
    }

    @Test
    fun `decomposed clusters and paragraph segmentation amplification fail explicitly`() {
        for (value in listOf("E\u0301", "aé".repeat(501))) {
            val parts = PdfFixture.unzip(source())
            val document = parts.getValue("word/document.xml").decodeToString()
            val changed = document.replace("R01 ASCII serif; É01 élève € Ω01 Ελληνικά Ж01 Отчёт.", value)
            assertNotEquals(document, changed)
            val source = PdfFixture.zip(parts.mapValues { (name, bytes) -> if (name == "word/document.xml") changed.toByteArray() else bytes })
            val error = assertThrows(NativeDocxReadException::class.java) { read(source) }
            assertTrue(error.message!!.contains("Mixed font selection"))
        }
    }
}
