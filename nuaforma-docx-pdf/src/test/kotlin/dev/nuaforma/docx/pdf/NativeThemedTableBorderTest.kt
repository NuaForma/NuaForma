package dev.nuaforma.docx.pdf

import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class NativeThemedTableBorderTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `solid theme-colored table and cell borders use the embedded palette`() {
        val support = NativeWordFeatureSupport(temporary)
        val parts = PdfFixture.unzip(support.bytes(source))
        val document = parts.getValue(part).decodeToString()
        val accent = Regex("""<a:accent1>\s*<a:srgbClr val="([0-9A-Fa-f]{6})"""")

        fun color(base: Boolean): String {
            val rgb = requireNotNull(accent.find(parts.getValue(theme).decodeToString())).groupValues[1]
            return if (base) """w:color="$rgb"""" else """w:color="DE2222" w:themeColor="accent1""""
        }

        fun image(table: Boolean, attrs: String) = Loader.loadPDF(support.generate(
            PdfFixture.zip(parts + (part to fixture(document, table, attrs).toByteArray())), "standard",
        )).use { PDFRenderer(it).renderImageWithDPI(0, 96f) }

        val borders = listOf(color(true) to color(false),
            """w:color="547FB4"""" to """w:color="DE2222" w:themeColor="text1" w:themeTint="99"""",
            """w:color="84A8CF"""" to """w:color="DE2222" w:themeColor="background1" w:themeShade="BF"""")
        for (table in listOf(true, false)) for ((explicit, themed) in borders) {
            assertImagesMatch(image(table, explicit), image(table, themed)) {
                pixelTolerance = 0.0
                allowedDifferentPixelsPercent = 0.0
                threshold = 1
            }
        }
    }

    @Test
    fun `solid table border with an invalid theme role retains a source diagnostic`() {
        val support = NativeWordFeatureSupport(temporary)
        for (invalid in listOf("""w:themeColor="unknownRole"""", """w:themeTint="99"""",
            """w:themeColor="text1" w:themeTint="Z9"""", """w:themeColor="text1" w:themeTint="99" w:themeShade="BF"""")) {
            support.rejectChanged(source, part, "top") { fixture(it, true, """w:color="DE2222" $invalid""") }
        }
    }

    private fun fixture(xml: String, wholeTable: Boolean, color: String): String {
        fun edge(name: String) = """<w:$name w:val="single" w:sz="6" w:space="0" $color/>"""
        val tableBorders = if (wholeTable) "<w:tblBorders>" +
            listOf("top", "left", "bottom", "right", "insideH", "insideV").joinToString("", transform = ::edge) + "</w:tblBorders>" else ""
        val cellBorders = if (wholeTable) "" else "<w:tcBorders>" + edge("top") + "</w:tcBorders>"
        val table = """<w:tbl><w:tblPr><w:tblW w:type="dxa" w:w="5000"/>$tableBorders<w:tblLayout w:type="fixed"/>""" +
            """<w:tblCellMar><w:top w:w="40" w:type="dxa"/><w:left w:w="80" w:type="dxa"/>""" +
            """<w:bottom w:w="40" w:type="dxa"/><w:right w:w="80" w:type="dxa"/></w:tblCellMar></w:tblPr>""" +
            """<w:tblGrid><w:gridCol w:w="2500"/><w:gridCol w:w="2500"/></w:tblGrid><w:tr>""" +
            cell("COLOR_EDGE", cellBorders) + cell("OTHER_CELL", "") + "</w:tr></w:tbl>"
        return xml.substringBefore("<w:body>") + "<w:body>$table<w:sectPr>" + xml.substringAfter("<w:sectPr>")
    }

    private fun cell(text: String, edge: String): String =
        """<w:tc><w:tcPr><w:tcW w:w="2500" w:type="dxa"/>$edge</w:tcPr>""" +
            """<w:p><w:pPr><w:spacing w:before="0" w:after="0" w:line="280" w:lineRule="exact"/></w:pPr>""" +
            "<w:r><w:t>$text</w:t></w:r></w:p></w:tc>"

    private companion object {
        const val source = "/theme-colors/a4/source.docx"
        const val part = "word/document.xml"
        const val theme = "word/theme/theme1.xml"
    }
}
