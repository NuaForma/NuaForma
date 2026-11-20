package dev.nuaforma.docx.pdf

import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class NativeTableLevelShadingTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `table shading paints no pixels without spacing while explicit cell paint remains`() {
        val support = NativeWordFeatureSupport(temporary)
        val parts = PdfFixture.unzip(support.bytes(source))
        val original = parts.getValue(part).decodeToString()

        fun generate(mark: String) = support.generate(PdfFixture.zip(parts + (part to fixture(original, mark).toByteArray())), "standard")

        Loader.loadPDF(generate("")).use { control ->
            val controlImage = PDFRenderer(control).renderImageWithDPI(0, 96f)
            val expected = support.positions(control, Regex("TABLECOLOR|CELLCOLOR"))
            assertEquals(2, expected.size)
            val variants = listOf(shading("D9D9D9"), shading("202040"),
                """<w:shd w:val="clear" w:fill="FCFCFC"/>""",
                """<w:shd w:val="clear" w:color="FF0000" w:fill="FCFCFC"/>""")
            for (shade in variants) {
                Loader.loadPDF(generate(shade)).use { modified ->
                    assertEquals(expected, support.positions(modified, Regex("TABLECOLOR|CELLCOLOR")))
                    assertImagesMatch(controlImage, PDFRenderer(modified).renderImageWithDPI(0, 96f)) {
                        pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                    }
                }
            }
        }
    }

    @Test
    fun `malformed shading and active cell spacing remain explicit source failures`() {
        val support = NativeWordFeatureSupport(temporary)
        support.rejectChanged(source, part, "shd") { fixture(it, shading("invalid")) }
        support.rejectChanged(source, part, "tblCellSpacing") {
            fixture(it, shading("FF0000")).replaceFirst("<w:tblLayout", """<w:tblCellSpacing w:w="100" w:type="dxa"/><w:tblLayout""")
        }
    }

    @Test
    fun `clear cell shading keeps its explicit fill regardless of the unused foreground`() {
        val support = NativeWordFeatureSupport(temporary)
        val parts = PdfFixture.unzip(support.bytes(source))
        val original = parts.getValue(part).decodeToString()
        val xml = fixture(original, "")

        fun image(cellShade: String): java.awt.image.BufferedImage {
            val modified = xml.replace(shading("A5C6E8"), cellShade)
            val pdf = support.generate(PdfFixture.zip(parts + (part to modified.toByteArray())), "standard")
            return Loader.loadPDF(pdf).use { PDFRenderer(it).renderImageWithDPI(0, 96f) }
        }

        val base = image(shading("A5C6E8"))
        for (shade in listOf("""<w:shd w:val="clear" w:fill="A5C6E8"/>""",
            """<w:shd w:val="clear" w:color="FF00FF" w:fill="A5C6E8"/>""")) {
            assertImagesMatch(base, image(shade)) {
                pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
            }
        }
        support.rejectChanged(source, part, "shd") {
            fixture(it, "").replace(shading("A5C6E8"), """<w:shd w:val="clear" w:color="invalid" w:fill="A5C6E8"/>""")
        }
    }

    private fun fixture(xml: String, tableShade: String): String {
        val table = """<w:tbl><w:tblPr><w:tblW w:type="dxa" w:w="5200"/>$tableShade<w:tblLayout w:type="fixed"/>""" +
            """<w:tblCellMar><w:top w:w="40" w:type="dxa"/><w:left w:w="80" w:type="dxa"/>""" +
            """<w:bottom w:w="40" w:type="dxa"/><w:right w:w="80" w:type="dxa"/></w:tblCellMar></w:tblPr>""" +
            """<w:tblGrid><w:gridCol w:w="2600"/><w:gridCol w:w="2600"/></w:tblGrid><w:tr>""" +
            cell("TABLECOLOR", "") + cell("CELLCOLOR", shading("A5C6E8")) + "</w:tr></w:tbl>"
        return xml.substringBefore("<w:body>") + "<w:body>$table<w:sectPr>" + xml.substringAfter("<w:sectPr>")
    }

    private fun cell(text: String, shade: String): String =
        """<w:tc><w:tcPr><w:tcW w:type="dxa" w:w="2600"/>$shade</w:tcPr>""" +
            """<w:p><w:pPr><w:spacing w:before="0" w:after="0" w:line="280" w:lineRule="exact"/></w:pPr>""" +
            "<w:r><w:t>$text</w:t></w:r></w:p></w:tc>"

    private fun shading(color: String) = """<w:shd w:val="clear" w:color="auto" w:fill="$color"/>"""

    private companion object {
        const val source = "/floating-drawings/control/a4/source.docx"
        const val part = "word/document.xml"
    }
}
