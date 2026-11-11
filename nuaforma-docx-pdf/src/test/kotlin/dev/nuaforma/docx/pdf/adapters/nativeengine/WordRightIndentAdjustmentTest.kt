package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.PdfFixture
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordRightIndentAdjustmentTest {
    private val word = PdfFixture.W
    private val office = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private fun read(flag: String, grid: String = "", style: Boolean = true, footer: Boolean = false, paragraphSection: Boolean = false) {
        val parts = PdfFixture.parts()
        val name = if (style) "word/styles.xml" else "word/document.xml"
        val marker = if (style) "</w:style>" else "<w:p><w:r>"
        val replacement = if (style) "<w:pPr>$flag</w:pPr></w:style>" else "<w:p><w:pPr>$flag</w:pPr><w:r>"
        parts[name] = parts.getValue(name).decodeToString().replace(marker, replacement).toByteArray()
        if (grid.isNotEmpty()) {
            val document = parts.getValue("word/document.xml").decodeToString()
            parts["word/document.xml"] = (if (paragraphSection)
                document.replace("<w:p><w:r>", "<w:p><w:pPr><w:sectPr>$grid</w:sectPr></w:pPr><w:r>")
                else document.replace("<w:sectPr>", "<w:sectPr>$grid")).toByteArray()
        }
        if (footer) {
            parts["word/document.xml"] = parts.getValue("word/document.xml").decodeToString()
                .replace("<w:pgMar ", "<w:pgMar w:header=\"720\" w:footer=\"720\" ").toByteArray()
            parts["word/footer1.xml"] = """<w:ftr xmlns:w="$word"><w:p><w:pPr>$flag</w:pPr><w:r><w:t>Footer</w:t></w:r></w:p></w:ftr>""".toByteArray()
            parts["word/_rels/document.xml.rels"] = parts.getValue("word/_rels/document.xml.rels").decodeToString()
                .replace("</Relationships>", """<Relationship Id="footer" Type="$office/footer" Target="footer1.xml"/></Relationships>""").toByteArray()
            parts["[Content_Types].xml"] = parts.getValue("[Content_Types].xml").decodeToString()
                .replace("</Types>", """<Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/></Types>""").toByteArray()
            parts["word/document.xml"] = parts.getValue("word/document.xml").decodeToString().replace("<w:sectPr>", """<w:sectPr><w:footerReference xmlns:r="$office" w:type="default" r:id="footer"/>""").toByteArray()
        }
        NativeDocxReader(GenerationContext()) { _, _, _ -> "regular" }.read(PdfFixture.zip(parts))
    }

    @Test
    fun `all defined switches retain layout with absent and default grids in styles and direct paragraphs`() {
        for (value in listOf("", " w:val=\"1\"", " w:val=\"0\"", " w:val=\"true\"", " w:val=\"false\"", " w:val=\"on\"", " w:val=\"off\""))
            for (style in listOf(false, true)) for (grid in listOf("", "<w:docGrid/>", "<w:docGrid w:type=\"default\" w:linePitch=\"360\"/>"))
                assertDoesNotThrow { read("<w:adjustRightInd$value/>", grid, style) }
        assertDoesNotThrow { read("<w:adjustRightInd/>", footer = true) }
    }

    @Test
    fun `malformed duplicate foreign and misplaced switches remain unsupported`() {
        for (bad in listOf("<w:adjustRightInd w:val=\"bad\"/>", "<w:adjustRightInd val=\"0\"/>",
            "<w:adjustRightInd extra=\"1\"/>", "<w:adjustRightInd/><w:adjustRightInd/>",
            "<w:adjustRightInd>text</w:adjustRightInd>", "<w:adjustRightInd><w:r/></w:adjustRightInd>",
            "<x:adjustRightInd xmlns:x=\"urn:unknown\"/>", "<w:rPr><w:adjustRightInd/></w:rPr>"))
            assertThrows(NativeDocxReadException::class.java, { read(bad) }, bad)
    }

    @Test
    fun `active grids remain rejected even with disabled adjustment and page bands`() {
        for (type in listOf("lines", "linesAndChars", "snapToChars")) for (flag in listOf("<w:adjustRightInd/>", "<w:adjustRightInd w:val=\"false\"/>")) {
                val grid = "<w:docGrid w:type=\"$type\" w:linePitch=\"360\"/>"
                assertThrows(NativeDocxReadException::class.java) { read(flag, grid, footer = true) }
                assertThrows(NativeDocxReadException::class.java) { read(flag, grid, paragraphSection = true) }
            }
    }

    @Test
    fun `invalid and duplicate grid structures do not disappear behind adjustment normalization`() {
        for (grid in listOf("<w:docGrid/><w:docGrid/>", "<w:docGrid w:linePitch=\"bad\"/>",
            "<w:docGrid w:type=\"default\" other=\"1\"/>", "<w:docGrid><w:r/></w:docGrid>",
            "<x:docGrid xmlns:x=\"urn:unknown\"/>"))
            assertThrows(NativeDocxReadException::class.java, { read("<w:adjustRightInd/>", grid) }, grid)
    }

}
