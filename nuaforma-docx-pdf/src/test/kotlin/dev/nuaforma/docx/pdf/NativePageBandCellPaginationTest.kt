package dev.nuaforma.docx.pdf

import org.apache.pdfbox.Loader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class NativePageBandCellPaginationTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `inherited and direct pagination flags do not change complete header and footer rows`() {
        val support = NativeWordFeatureSupport(temporary)

        fun bandMarkers(flags: String, inherited: Boolean = false): Map<String, NativeWordFeatureSupport.Position> {
            val parts = PdfFixture.unzip(support.bytes(source)).toMutableMap()
            val properties = if (inherited) {
                val style = """<w:style w:type="paragraph" w:styleId="BandPagination"><w:name w:val="Band Pagination"/>""" +
                    """<w:basedOn w:val="Normal"/><w:pPr>$flags</w:pPr></w:style>"""
                for (styles in listOf("word/styles.xml", "word/stylesWithEffects.xml")) {
                    parts[styles] = parts.getValue(styles).decodeToString().replace("</w:styles>", "$style</w:styles>").toByteArray()
                }
                """<w:pStyle w:val="BandPagination"/>"""
            } else flags
            for (band in listOf("header", "footer")) {
                val part = "word/${band}1.xml"
                parts[part] = tableInBand(parts.getValue(part).decodeToString(), band, properties).toByteArray()
            }
            return Loader.loadPDF(support.generate(PdfFixture.zip(parts), "standard")).use { pdf ->
                assertEquals(1, pdf.numberOfPages)
                support.positions(pdf, Regex("(HEADER|FOOTER)_(ONE|TWO)"))
            }
        }

        val control = bandMarkers("")
        assertEquals(setOf("HEADER_ONE", "HEADER_TWO", "FOOTER_ONE", "FOOTER_TWO"), control.keys)
        val flags = "<w:keepNext/><w:keepLines/><w:widowControl/>"
        assertEquals(control, bandMarkers(flags))
        assertEquals(control, bandMarkers(flags, inherited = true))
    }

    @Test
    fun `malformed pagination in page bands is still source diagnosed`() {
        val support = NativeWordFeatureSupport(temporary)
        support.rejectChanged(source, "word/footer1.xml", "keepNext") {
            tableInBand(it, "footer", """<w:keepNext bad="true"/>""")
        }
    }

    private fun tableInBand(xml: String, band: String, flags: String): String {
        val root = if (band == "header") "hdr" else "ftr"
        val before = requireNotNull(Regex("(.*?<w:$root\\b[^>]*>)", RegexOption.DOT_MATCHES_ALL).find(xml)).value
        val marker = band.uppercase()
        return before + """<w:tbl><w:tblPr><w:tblW w:w="4500" w:type="dxa"/><w:tblLayout w:type="fixed"/>""" +
            """<w:tblCellMar><w:top w:w="0" w:type="dxa"/><w:left w:w="0" w:type="dxa"/>""" +
            """<w:bottom w:w="0" w:type="dxa"/><w:right w:w="0" w:type="dxa"/></w:tblCellMar></w:tblPr>""" +
            """<w:tblGrid><w:gridCol w:w="4500"/></w:tblGrid><w:tr><w:tc><w:tcPr><w:tcW w:w="4500" w:type="dxa"/></w:tcPr>""" +
            """<w:p><w:pPr>$flags<w:spacing w:line="280" w:lineRule="exact"/></w:pPr><w:r><w:t>${marker}_ONE</w:t></w:r></w:p>""" +
            """<w:p><w:pPr>$flags<w:spacing w:line="280" w:lineRule="exact"/></w:pPr><w:r><w:t>${marker}_TWO</w:t></w:r></w:p>""" +
            "</w:tc></w:tr></w:tbl></w:$root>"
    }

    private companion object {
        const val source = "/soft-line-breaks/table-bands/a4/source.docx"
    }
}
