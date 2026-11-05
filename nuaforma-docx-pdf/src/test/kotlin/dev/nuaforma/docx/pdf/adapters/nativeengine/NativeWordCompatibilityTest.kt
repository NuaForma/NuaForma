package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.*
import dev.nuaforma.pdf.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.*

class NativeWordCompatibilityTest {

    private fun source(properties: String, content: String = "<w:r><w:t>VISIBLE</w:t></w:r>", styles: String = ""): ByteArray {
        val input = requireNotNull(javaClass.getResourceAsStream("/native-profile/word-letter.docx")).use { it.readBytes() }
        return ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip -> ZipInputStream(input.inputStream()).use { original ->
            while (true) {
                val entry = original.nextEntry ?: break
                val bytes = original.readBytes()
                val changed = when (entry.name) {
                    "word/document.xml" -> bytes.decodeToString().replace("<w:body>", "<w:body><w:p><w:pPr>$properties</w:pPr>$content</w:p>").toByteArray()
                    "word/styles.xml" -> bytes.decodeToString().replace("</w:styles>", styles + "</w:styles>").toByteArray()
                    else -> bytes
                }
                zip.putNextEntry(ZipEntry(entry.name)); zip.write(changed); zip.closeEntry()
            }
        } } }.toByteArray()
    }

    private fun context() = GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))

    private fun read(bytes: ByteArray) = NativeDocxReader(context()) { _, bold, _ -> if (bold) "bold" else "regular" }.read(bytes)

    @Test
    fun `paragraph mark highlight is retained without coloring visible text`() {
        for (color in listOf("none", "yellow", "green", "darkBlue", "lightGray")) {
            val bytes = source("<w:rPr><w:highlight w:val=\"$color\"/></w:rPr>")
            val output = ByteArrayOutputStream()
            val report = NativeDocxPdfProvider(NativePdfConfiguration(emptyList())).cleanTemplate(bytes.inputStream(), output, context())
            assertTrue(report.changes.isEmpty(), report.changes.toString())
            assertArrayEquals(bytes, output.toByteArray())
            assertEquals("VISIBLE", ((read(bytes).blocks.first() as PdfParagraph).runs.first() as PdfTextRun).text)
        }
        for (highlight in listOf("<w:highlight/>", "<w:highlight w:val=\"unknown\"/>",
            "<w:highlight w:val=\"none\"/><w:highlight w:val=\"yellow\"/>", "<w:highlight w:val=\"none\"><w:b/></w:highlight>")) {
            assertThrows(NativeDocxReadException::class.java) { read(source("<w:rPr>$highlight</w:rPr>")) }
        }
        assertThrows(NativeDocxReadException::class.java) {
            read(source("", "<w:r><w:rPr><w:highlight w:val=\"yellow\"/></w:rPr><w:t>VISIBLE</w:t></w:r>"))
        }
    }

    @Test
    fun `paragraph marks retain nonprinting metrics without restyling visible text`() {
        val bytes = source("<w:spacing w:line=\"240\" w:lineRule=\"auto\"/><w:rPr><w:b/><w:sz w:val=\"32\"/><w:szCs w:val=\"32\"/></w:rPr>")
        val p = read(bytes).blocks.first() as PdfParagraph
        assertEquals(16f, p.minimumLineStyle?.size)
        assertEquals("bold", p.minimumLineStyle?.fontId)
        assertEquals("regular", (p.runs.first() as PdfTextRun).style.fontId)
        val output = ByteArrayOutputStream()
        val report = NativeDocxPdfProvider(NativePdfConfiguration(emptyList())).cleanTemplate(bytes.inputStream(), output, context())
        assertArrayEquals(bytes, output.toByteArray())
        assertTrue(report.changes.isEmpty())
    }

    @Test
    fun `signed left and right indents and hanging precedence survive source translation`() {
        val p = read(source("<w:ind w:left=\"-120\" w:right=\"-80\" w:firstLine=\"240\" w:hanging=\"60\"/>")).blocks.first() as PdfParagraph
        assertEquals(-6f, p.format.leftIndent)
        assertEquals(-4f, p.format.rightIndent)
        assertEquals(-3f, p.format.firstLineIndent)
    }

    @Test
    fun `empty formatting runs neither demand unused faces nor trigger cascading cleanup`() {
        val bytes = source("", "<w:r><w:rPr><w:rFonts w:ascii=\"Unused Face\" w:hAnsi=\"Unused Face\"/></w:rPr></w:r><w:r><w:t>VISIBLE</w:t></w:r>")
        assertEquals("VISIBLE", ((read(bytes).blocks.first() as PdfParagraph).runs.single() as PdfTextRun).text)
        val output = ByteArrayOutputStream()
        val report = NativeDocxPdfProvider(NativePdfConfiguration(emptyList())).cleanTemplate(bytes.inputStream(), output, context())
        assertTrue(report.changes.isEmpty())
        assertArrayEquals(bytes, output.toByteArray())
    }

    @Test
    fun `nonprinting mark faces do not require fonts when an exact visible line fixes its own geometry`() {
        val bytes = source("""<w:spacing w:line="320" w:lineRule="exact"/><w:rPr><w:rFonts w:ascii="Unused Face" w:hAnsi="Unused Face"/></w:rPr>""")
        val document = NativeDocxReader(context()) { family, _, _ ->
            assertNotEquals("Unused Face", family)
            "regular"
        }.read(bytes)
        val paragraph = document.blocks.first() as PdfParagraph
        assertNull(paragraph.minimumLineStyle)
        assertEquals("VISIBLE", (paragraph.runs.single() as PdfTextRun).text)
    }

    @Test
    fun `paragraph mark character styles affect line metrics without restyling visible runs`() {
        val bytes = source("""<w:spacing w:line="240" w:lineRule="auto"/><w:rPr><w:rStyle w:val="MarkChar"/></w:rPr>""", styles =
            """<w:style w:type="character" w:styleId="MarkChar"><w:name w:val="Mark Character"/><w:rPr><w:b/><w:sz w:val="36"/></w:rPr></w:style>""")
        val paragraph = read(bytes).blocks.first() as PdfParagraph
        assertEquals(18f, paragraph.minimumLineStyle?.size)
        assertEquals("bold", paragraph.minimumLineStyle?.fontId)
        assertEquals("regular", (paragraph.runs.single() as PdfTextRun).style.fontId)
    }

    @Test
    fun `invalid indentation removes only its own attribute with original values in the report`() {
        val output = ByteArrayOutputStream()
        val report = NativeDocxPdfProvider(NativePdfConfiguration(emptyList())).cleanTemplate(
            source("<w:ind w:left=\"240\" w:firstLine=\"-10\"/>").inputStream(), output, context())
        val p = read(output.toByteArray()).blocks.first() as PdfParagraph
        assertEquals(12f, p.format.leftIndent)
        assertEquals(0f, p.format.firstLineIndent)
        assertEquals(1, report.changes.size)
        assertEquals(TemplateCleanupAction.REMOVE_ATTRIBUTE, report.changes.single().action)
        assertTrue(report.changes.single().reason.contains("firstLine=-10"))
    }
}
