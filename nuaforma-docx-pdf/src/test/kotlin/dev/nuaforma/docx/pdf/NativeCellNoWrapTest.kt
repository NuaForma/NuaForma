package dev.nuaforma.docx.pdf

import org.apache.pdfbox.Loader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class NativeCellNoWrapTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `the physical preference takes priority only for an enabled automatic cell`() {
        val support = NativeWordFeatureSupport(temporary)

        fun firstColumnWidth(toggle: String, fixed: Boolean = false): Float {
            val parts = PdfFixture.unzip(support.bytes(source)).toMutableMap()
            parts[part] = fixture(parts.getValue(part).decodeToString(), toggle, fixed).toByteArray()
            return Loader.loadPDF(support.generate(PdfFixture.zip(parts), "standard")).use { pdf ->
                val positions = support.positions(pdf, Regex("AAAA|BBBB"))
                positions.getValue("BBBB").x - positions.getValue("AAAA").x
            }
        }

        val unprotected = firstColumnWidth("")
        assertTrue(unprotected < 170f)
        for (disabled in listOf("false", "0", "off")) {
            assertEquals(unprotected, firstColumnWidth("""<w:noWrap w:val="$disabled"/>"""), .01f, disabled)
        }
        assertEquals(180f, firstColumnWidth("<w:noWrap/>"), .01f)
        assertEquals(180f, firstColumnWidth("<w:noWrap/>", fixed = true), .01f)
        assertEquals(180f, firstColumnWidth("", fixed = true), .01f)
    }

    @Test
    fun `malformed and out of profile toggles fail at their original element`() {
        val support = NativeWordFeatureSupport(temporary)
        for (invalid in listOf("""<w:noWrap val="1"/>""", """<w:noWrap w:val="other"/>""",
            "<w:noWrap>text</w:noWrap>", "<w:noWrap/><w:noWrap/>")) {
            support.rejectChanged(source, part, "noWrap") { fixture(it, invalid) }
        }
        support.rejectChanged(source, part, "noWrap") {
            fixture(it, "<w:noWrap/>").replace("""<w:tcW w:w="3600" w:type="dxa"/>""", """<w:tcW w:w="2500" w:type="pct"/>""")
        }
    }

    private fun fixture(xml: String, toggle: String, fixed: Boolean = false): String {
        val tableWidth = if (fixed) 6800 else 5800
        val layout = if (fixed) "fixed" else "autofit"
        return xml.substringBefore("<w:body>") +
            """<w:body><w:tbl><w:tblPr><w:tblW w:w="$tableWidth" w:type="dxa"/><w:tblLayout w:type="$layout"/>""" +
            """<w:tblCellMar><w:top w:w="0" w:type="dxa"/><w:left w:w="80" w:type="dxa"/>""" +
            """<w:bottom w:w="0" w:type="dxa"/><w:right w:w="80" w:type="dxa"/></w:tblCellMar></w:tblPr>""" +
            """<w:tblGrid><w:gridCol w:w="3600"/><w:gridCol w:w="3200"/></w:tblGrid><w:tr>""" +
            """<w:tc><w:tcPr><w:tcW w:w="3600" w:type="dxa"/>$toggle</w:tcPr>""" +
            """<w:p><w:r><w:t>AAAA alpha alpha alpha alpha alpha alpha</w:t></w:r></w:p></w:tc>""" +
            """<w:tc><w:tcPr><w:tcW w:w="3200" w:type="dxa"/></w:tcPr>""" +
            """<w:p><w:r><w:t>BBBB cccc dddd eeee ffff gggg hhhh iiii jjjj kkkk llll mmmm</w:t></w:r></w:p></w:tc>""" +
            "</w:tr></w:tbl><w:sectPr>" + xml.substringAfter("<w:sectPr>")
    }

    private companion object {
        const val source = "/floating-drawings/control/a4/source.docx"
        const val part = "word/document.xml"
    }
}
