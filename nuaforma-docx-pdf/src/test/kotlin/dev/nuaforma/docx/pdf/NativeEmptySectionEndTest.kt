package dev.nuaforma.docx.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path

class NativeEmptySectionEndTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest(name = "{0}")
    @CsvSource("A4,11906,16838,48,530", "Letter,12240,15840,45,372")
    fun `empty table section terminators do not consume an extra page`(
        format: String, width: Int, height: Int, fillerCount: Int, firstLine: Int,
    ) {
        val support = NativeWordFeatureSupport(temporary)
        val profiles = listOf("standard", "pdfa-3b")
        for (profile in profiles) {
            fun pages(properties: String, contents: String): List<String> = Loader.loadPDF(support.generate(
                source(width, height, fillerCount, firstLine, properties, contents), profile,
            )).use { pdf ->
                (1..pdf.numberOfPages).map { page ->
                    PDFTextStripper().apply { startPage = page; endPage = page }.getText(pdf).trim()
                }
            }

            val ordinary = pages("", "")
            assertEquals(2, ordinary.size, "$format $profile")
            assertTrue(ordinary.first().contains("TABLE_END"), "$format $profile")
            assertEquals("NEXT_SECTION", ordinary.last())

            val forced = pages("<w:pageBreakBefore/>", "")
            assertEquals(listOf(ordinary.first(), "", ordinary.last()), forced, "$format $profile")

            val visible = pages("", """<w:r><w:t>VISIBLE_SECTION_END</w:t></w:r>""")
            assertEquals(listOf(ordinary.first(), "VISIBLE_SECTION_END", ordinary.last()), visible, "$format $profile")
        }
    }

    private fun source(width: Int, height: Int, fillerCount: Int, firstLine: Int, properties: String, contents: String): ByteArray {
        val section = """<w:sectPr><w:pgSz w:w="$width" w:h="$height"/>""" +
            """<w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134"/></w:sectPr>"""
        val tableWidth = width - 2268
        val table = """<w:tbl><w:tblPr><w:tblW w:w="$tableWidth" w:type="dxa"/><w:tblLayout w:type="fixed"/>""" +
            """<w:tblCellMar><w:top w:w="0" w:type="dxa"/><w:left w:w="108" w:type="dxa"/>""" +
            """<w:bottom w:w="0" w:type="dxa"/><w:right w:w="108" w:type="dxa"/></w:tblCellMar></w:tblPr>""" +
            """<w:tblGrid><w:gridCol w:w="$tableWidth"/></w:tblGrid><w:tr><w:tc>""" +
            """<w:tcPr><w:tcW w:w="$tableWidth" w:type="dxa"/></w:tcPr>""" +
            line("TABLE_START") + line("TABLE_END") + "</w:tc></w:tr></w:tbl>"
        val terminator = "<w:p><w:pPr>$properties${spacing(256)}$section</w:pPr>$contents</w:p>"
        val parts = PdfFixture.parts()
        parts["word/styles.xml"] = parts.getValue("word/styles.xml").decodeToString()
            .replace("DejaVu Sans", "Liberation Sans").replace("""w:val="22"""", """w:val="20"""").toByteArray()
        parts["word/document.xml"] = ("""<w:document xmlns:w="${PdfFixture.W}"><w:body>""" +
            line("FIRST_SECTION", firstLine) + line("BODY_FILLER").repeat(fillerCount) + table + terminator +
            line("NEXT_SECTION") + section + "</w:body></w:document>").toByteArray()
        return PdfFixture.zip(parts)
    }

    private fun line(text: String, height: Int = 280) =
        "<w:p><w:pPr>${spacing(height)}</w:pPr><w:r><w:t>$text</w:t></w:r></w:p>"

    private fun spacing(height: Int) = """<w:spacing w:before="0" w:after="0" w:line="$height" w:lineRule="exact"/>"""
}
