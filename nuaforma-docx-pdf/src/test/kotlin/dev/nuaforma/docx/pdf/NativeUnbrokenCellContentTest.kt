package dev.nuaforma.docx.pdf

import org.apache.pdfbox.Loader
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class NativeUnbrokenCellContentTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `automatic and percentage cells request the entire unbroken line`() {
        for (type in listOf("pct", "auto")) {
            val ordinary = positions(type, false)
            val unbroken = positions(type, true)
            assertEquals(ordinary.keys, unbroken.keys)
            assertTrue(ordinary.getValue("HERE").y > ordinary.getValue("WORDS").y + 10f)
            assertEquals(unbroken.getValue("WORDS").y, unbroken.getValue("HERE").y, .05f)
            assertTrue(unbroken.getValue("RIGHT").x > ordinary.getValue("RIGHT").x, "Unbroken $type content must widen its column")
        }
    }

    @Test
    fun `physical preferred widths keep their earlier semantics`() {
        val ordinary = positions("dxa", false)
        val protected = positions("dxa", true)
        assertEquals(ordinary, protected)
        assertTrue(protected.getValue("HERE").y > protected.getValue("WORDS").y + 10f)
    }

    private fun positions(type: String, noWrap: Boolean): Map<String, NativeWordFeatureSupport.Position> {
        val support = NativeWordFeatureSupport(temporary)
        val parts = PdfFixture.unzip(support.bytes("/floating-drawings/control/a4/source.docx")).toMutableMap()
        val document = parts.getValue("word/document.xml").decodeToString()
        val amount = when (type) {
            "pct" -> 1250
            "dxa" -> 1300
            else -> 0
        }
        val toggle = if (noWrap) "<w:noWrap/>" else ""
        val table = """<w:tbl><w:tblPr><w:tblW w:type="dxa" w:w="5200"/><w:tblLayout w:type="autofit"/>""" +
            """<w:tblCellMar><w:top w:w="0" w:type="dxa"/><w:left w:w="108" w:type="dxa"/>""" +
            """<w:bottom w:w="0" w:type="dxa"/><w:right w:w="108" w:type="dxa"/></w:tblCellMar></w:tblPr>""" +
            """<w:tblGrid><w:gridCol w:w="1300"/><w:gridCol w:w="3900"/></w:tblGrid><w:tr>""" +
            cell(type, amount, toggle, "WORDS STAY HERE") + cell("dxa", 3900, "", "RIGHT") + "</w:tr></w:tbl>"
        parts["word/document.xml"] = (document.substringBefore("<w:body>") + "<w:body>$table<w:sectPr>" +
            document.substringAfter("<w:sectPr>")).toByteArray()
        val settings = parts.getValue("word/settings.xml").decodeToString()
        val mode = """<w:compatSetting w:name="compatibilityMode" w:uri="http://schemas.microsoft.com/office/word" w:val="15"/>"""
        parts["word/settings.xml"] = (if ("</w:compat>" in settings) settings.replace("</w:compat>", "$mode</w:compat>")
            else settings.replace("</w:settings>", "<w:compat>$mode</w:compat></w:settings>")).toByteArray()
        return Loader.loadPDF(support.generate(PdfFixture.zip(parts), "standard")).use { pdf ->
            assertEquals(1, pdf.numberOfPages)
            support.positions(pdf, Regex("WORDS|HERE|RIGHT"))
        }
    }

    private fun cell(type: String, width: Int, property: String, text: String) =
        """<w:tc><w:tcPr><w:tcW w:type="$type" w:w="$width"/>$property</w:tcPr><w:p><w:pPr>""" +
            """<w:spacing w:before="0" w:after="0" w:line="280" w:lineRule="exact"/></w:pPr>""" +
            """<w:r><w:t>$text</w:t></w:r></w:p></w:tc>"""
}
