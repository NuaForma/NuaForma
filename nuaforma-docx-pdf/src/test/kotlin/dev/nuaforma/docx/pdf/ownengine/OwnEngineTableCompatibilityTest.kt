package dev.nuaforma.docx.pdf.ownengine

import dev.nuaforma.pdf.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class OwnEngineTableCompatibilityTest {
    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes() }
    private fun source() = resource("/own-engine-report/source.docx")
    private fun fonts() = listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic").map { (id, file) ->
        PdfFont(id, resource("/own-engine-fonts/LiberationSans-$file.ttf"))
    }

    @ParameterizedTest @ValueSource(strings = ["absent", "12", "15"])
    fun `compatibility mode preserves report content and selects the source table alignment`(mode: String) {
        val reader = OwnEngineDocxReader()
        val report = reportWithRoom()
        val legacy = reader.read(report)
        val candidate = reader.read(if (mode == "absent") report else settings(report, listOf(mode)))
        val before = legacy.blocks.filterIsInstance<PdfTable>().single()
        val after = candidate.blocks.filterIsInstance<PdfTable>().single()
        val shift = if (mode == "15") before.cellMargins.left + requireNotNull(before.border).width / 2 else 0f
        assertEquals(before.leftIndent + shift, after.leftIndent, 0.0001f)
        assertEquals(before.columnWidths, after.columnWidths)
        assertEquals(before.repeatedHeaderRows, after.repeatedHeaderRows)
        Loader.loadPDF(PdfEngine().render(legacy, fonts())).use { original ->
            Loader.loadPDF(PdfEngine().render(candidate, fonts())).use { actual ->
                assertEquals(3, actual.numberOfPages)
                assertEquals(PDFTextStripper().getText(original).replace(Regex("\\s+"), ""),
                    PDFTextStripper().getText(actual).replace(Regex("\\s+"), ""))
                for (page in 1..2) {
                    assertEquals(headerX(original, page) + shift, headerX(actual, page), 0.001f)
                    assertTrue(actual.getPage(page - 1).resources.fontNames.all { actual.getPage(page - 1).resources.getFont(it).isEmbedded })
                }
                assertEquals(original.getPage(0).mediaBox.width, actual.getPage(0).mediaBox.width)
            }
        }
    }

    @Test fun `modern indentation that exceeds the page rejects without changing declared column widths`() {
        val model = OwnEngineDocxReader().read(settings(source(), listOf("15")))
        assertEquals(PdfFailureCode.CONTENT_OVERFLOW, assertThrows(PdfRenderException::class.java) {
            PdfEngine().render(model, fonts())
        }.code)
    }

    @Test fun `modern outer table alignment does not apply a second shift inside nested tables`() {
        val source = resource("/own-engine-border-combinations/nested/source.docx")
        val legacy = OwnEngineDocxReader().read(settings(source, listOf("12"))).blocks.filterIsInstance<PdfTable>().single()
        val modern = OwnEngineDocxReader().read(settings(source, listOf("15"))).blocks.filterIsInstance<PdfTable>().single()
        fun nested(table: PdfTable) = table.rows.flatMap { it.cells }.flatMap { it.blocks }.filterIsInstance<PdfTable>()
        assertEquals(10, nested(modern).size)
        assertEquals(nested(legacy).map { it.leftIndent }, nested(modern).map { it.leftIndent })
        assertEquals(legacy.leftIndent + legacy.cellMargins.left + requireNotNull(legacy.border).width / 2, modern.leftIndent, 0.0001f)
    }

    @Test fun `ambiguous and unqualified modes reject instead of guessing table geometry`() {
        for (modes in listOf(listOf("12", "15"), listOf("15", "15"), listOf("14"), listOf("unknown"))) {
            assertThrows(IllegalArgumentException::class.java) { OwnEngineDocxReader().read(settings(source(), modes)) }
        }
    }

    private fun headerX(pdf: org.apache.pdfbox.pdmodel.PDDocument, page: Int): Float {
        val positions = mutableListOf<TextPosition>()
        object : PDFTextStripper() {
            init { startPage = page; endPage = page }
            override fun processTextPosition(position: TextPosition) { positions += position }
        }.getText(pdf)
        return positions.windowed(4).first { group -> group.joinToString("") { it.unicode } == "Code" }.first().x
    }

    private fun settings(source: ByteArray, modes: List<String>): ByteArray {
        val parts = entries(source)
        val namespace = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        parts["word/settings.xml"] = ("<w:settings xmlns:w=\"$namespace\"><w:compat>" + modes.joinToString("") {
            "<w:compatSetting w:name=\"compatibilityMode\" w:uri=\"http://schemas.microsoft.com/office/word\" w:val=\"$it\"/>"
        } + "</w:compat></w:settings>").toByteArray()
        val relation = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings"
        parts["word/_rels/document.xml.rels"] = parts.getValue("word/_rels/document.xml.rels").toString(Charsets.UTF_8)
            .replace("</Relationships>", "<Relationship Id=\"compatSettings\" Type=\"$relation\" Target=\"settings.xml\"/></Relationships>").toByteArray()
        parts["[Content_Types].xml"] = parts.getValue("[Content_Types].xml").toString(Charsets.UTF_8)
            .replace("</Types>", "<Override PartName=\"/word/settings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml\"/></Types>").toByteArray()
        return zip(parts)
    }

    private fun reportWithRoom(): ByteArray {
        val parts = entries(source())
        // The original report uses the entire width in legacy mode. Leave explicit
        // room for its modern indentation, without resizing any declared columns.
        val original = parts.getValue("word/document.xml").toString(Charsets.UTF_8)
        val roomy = original.replace("<w:pgSz w:w=\"11906\"", "<w:pgSz w:w=\"12306\"")
        check(roomy != original)
        parts["word/document.xml"] = roomy.toByteArray()
        return zip(parts)
    }

    private fun entries(source: ByteArray): LinkedHashMap<String, ByteArray> {
        val parts = linkedMapOf<String, ByteArray>()
        ZipInputStream(source.inputStream()).use { input ->
            while (true) { val entry = input.nextEntry ?: break; parts[entry.name] = input.readBytes() }
        }
        return parts
    }

    private fun zip(parts: Map<String, ByteArray>): ByteArray {
        return ByteArrayOutputStream().also { buffer -> ZipOutputStream(buffer).use { output ->
            parts.forEach { (name, bytes) -> output.putNextEntry(ZipEntry(name)); output.write(bytes); output.closeEntry() }
        } }.toByteArray()
    }
}
