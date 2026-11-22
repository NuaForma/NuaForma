package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.GenerationException
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class NativeAutomaticCellTabsTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `a fitting plain terminal tab preserves words positions and pixels in an automatic table`() {
        val support = NativeWordFeatureSupport(temporary)
        val markers = Regex("START_WIDE_FIELD|OTHER_CELL|BELOW_TABLE")
        val preservedSpaces = """<w:r><w:t xml:space="preserve">  </w:t></w:r>"""
        for (width in listOf(112, 240)) for (prefix in listOf("", preservedSpaces)) {
            val control = support.generate(template(support, prefix, width), "standard")
            val changed = support.generate(template(support, prefix + terminalTab, width), "standard")
            Loader.loadPDF(control).use { before ->
                Loader.loadPDF(changed).use { after ->
                    val expected = support.positions(before, markers)
                    assertEquals(3, expected.size)
                    assertEquals(expected, support.positions(after, markers))
                    assertImagesMatch(PDFRenderer(before).renderImageWithDPI(0, 96f), PDFRenderer(after).renderImageWithDPI(0, 96f)) {
                        pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                    }
                }
            }
        }
    }

    @Test
    fun `visible text after a tab still fails explicitly in an automatic table`() {
        val support = NativeWordFeatureSupport(temporary)
        val suffix = terminalTab + "<w:r><w:t>AFTER_TAB</w:t></w:r>"
        val failure = assertThrows(GenerationException::class.java) {
            support.generate(template(support, suffix), "standard")
        }
        val detail = failure.diagnostics.single().sourceContext?.explanation.orEmpty()
        assertTrue(detail.contains("Automatic table widths support only undecorated terminal default tabs"), detail)
    }

    private fun template(support: NativeWordFeatureSupport, suffix: String, width: Int = 240): ByteArray {
        val parts = PdfFixture.unzip(support.bytes("/floating-drawings/control/a4/source.docx")).toMutableMap()
        val name = "word/document.xml"
        val xml = parts.getValue(name).decodeToString()
        val column = width * 20
        val total = column + 2600
        parts[name] = (xml.substringBefore("<w:body>") +
            """<w:body><w:tbl><w:tblPr><w:tblW w:w="$total" w:type="dxa"/><w:tblLayout w:type="autofit"/>""" +
            """<w:tblCellMar><w:top w:w="0" w:type="dxa"/><w:left w:w="80" w:type="dxa"/>""" +
            """<w:bottom w:w="0" w:type="dxa"/><w:right w:w="80" w:type="dxa"/></w:tblCellMar></w:tblPr>""" +
            """<w:tblGrid><w:gridCol w:w="$column"/><w:gridCol w:w="2600"/></w:tblGrid><w:tr>""" +
            """<w:tc><w:tcPr><w:tcW w:type="dxa" w:w="$column"/></w:tcPr>""" +
            paragraph("START_WIDE_FIELD", suffix) + """</w:tc><w:tc><w:tcPr><w:tcW w:type="dxa" w:w="2600"/></w:tcPr>""" +
            paragraph("OTHER_CELL") + "</w:tc></w:tr></w:tbl>" + paragraph("BELOW_TABLE") +
            "<w:sectPr>" + xml.substringAfter("<w:sectPr>")).toByteArray()
        return PdfFixture.zip(parts)
    }

    private fun paragraph(text: String, suffix: String = ""): String =
        """<w:p><w:pPr><w:spacing w:before="0" w:after="0" w:line="280" w:lineRule="exact"/></w:pPr>""" +
            "<w:r><w:t>$text</w:t></w:r>$suffix</w:p>"

    private companion object {
        const val terminalTab = "<w:r><w:tab/></w:r>"
    }
}
