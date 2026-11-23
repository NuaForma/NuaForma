package dev.nuaforma.docx.pdf

import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class NativeIndependentTableEdgesTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `direct absent sides keep the other visible edges inherited from the table style`() {
        val support = NativeWordFeatureSupport(temporary)
        val original = PdfFixture.unzip(support.bytes(source))
        val base = original.getValue(part).decodeToString()
        val styleParts = setOf("word/styles.xml", "word/stylesWithEffects.xml")
        val styles = original.filterKeys { it in styleParts }.mapValues { (_, value) ->
            val definition = """<w:style w:type="table" w:styleId="SparseEdges"><w:name w:val="Sparse Edges"/>""" +
                "<w:tblPr>" + tableBorders(borderNames.toSet()) + "</w:tblPr></w:style>"
            value.decodeToString().replace("</w:styles>", definition + "</w:styles>").toByteArray()
        }
        val settingsPart = "word/settings.xml"
        val settings = original.getValue(settingsPart).decodeToString().replace("""w:val="14"/>""", """w:val="15"/>""")

        fun image(styled: Boolean) = Loader.loadPDF(support.generate(
            PdfFixture.zip(original + styles + (settingsPart to settings.toByteArray()) + (part to fixture(base, styled).toByteArray())),
            "standard",
        )).use { PDFRenderer(it).renderImageWithDPI(0, 96f) }

        assertImagesMatch(image(false), image(true)) {
            pixelTolerance = 0.0
            allowedDifferentPixelsPercent = 0.0
            threshold = 1
        }
    }

    private fun fixture(xml: String, styled: Boolean): String {
        val style = if (styled) """<w:tblStyle w:val="SparseEdges"/>""" else ""
        val borders = if (styled) """<w:tblBorders><w:left w:val="none"/><w:right w:val="none"/></w:tblBorders>"""
            else tableBorders(setOf("top", "bottom", "insideH", "insideV"))
        val table = """<w:tbl><w:tblPr>$style<w:tblW w:type="dxa" w:w="5000"/>$borders<w:tblLayout w:type="fixed"/>""" +
            """<w:tblCellMar><w:top w:w="0" w:type="dxa"/><w:left w:w="108" w:type="dxa"/>""" +
            """<w:bottom w:w="0" w:type="dxa"/><w:right w:w="108" w:type="dxa"/></w:tblCellMar></w:tblPr>""" +
            """<w:tblGrid><w:gridCol w:w="2500"/><w:gridCol w:w="2500"/></w:tblGrid><w:tr>""" +
            cell("GRID_LEFT") + cell("GRID_RIGHT") + "</w:tr><w:tr>" +
            cell("BOTTOM_LEFT") + cell("BOTTOM_RIGHT") + "</w:tr></w:tbl>"
        return xml.substringBefore("<w:body>") + "<w:body>$table<w:sectPr>" + xml.substringAfter("<w:sectPr>")
    }

    private fun tableBorders(visible: Set<String>): String =
        "<w:tblBorders>" + borderNames.joinToString("") { name ->
            if (name in visible) """<w:$name w:val="single" w:sz="4" w:color="000000"/>""" else """<w:$name w:val="nil"/>"""
        } + "</w:tblBorders>"

    private fun cell(text: String): String =
        """<w:tc><w:tcPr><w:tcW w:w="2500" w:type="dxa"/></w:tcPr>""" +
            """<w:p><w:pPr><w:spacing w:before="0" w:after="0" w:line="280" w:lineRule="exact"/></w:pPr>""" +
            "<w:r><w:t>$text</w:t></w:r></w:p></w:tc>"

    private companion object {
        const val source = "/theme-colors/a4/source.docx"
        const val part = "word/document.xml"
        val borderNames = listOf("top", "left", "bottom", "right", "insideH", "insideV")
    }
}
