package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.GenerationException
import org.apache.pdfbox.Loader
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Path

class NativeVerticalContinuationTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `thicker continuation edges increase row clearance and preserve origin text`() {
        val thin = positions(border = 16)
        val thick = positions(border = 48)
        val raised = positions(border = 48, continuationFloor = 640)
        assertEquals(thin.keys, thick.keys)
        assertEquals(thin.getValue("ORIGIN"), thick.getValue("ORIGIN"))
        assertEquals(4f, thick.getValue("AFTER").y - thin.getValue("AFTER").y, .05f)
        assertEquals(18f, raised.getValue("AFTER").y - thick.getValue("AFTER").y, .05f)
    }

    @Test
    fun `changed visible continuation sides remain unsupported`() {
        val support = NativeWordFeatureSupport(temporary)
        val output = ByteArrayOutputStream()
        val failure = assertThrows(GenerationException::class.java) {
            support.generate(document(support, 48, null, 48), "standard", output)
        }
        assertEquals(0, output.size())
        val diagnostic = failure.diagnostics.single()
        assertEquals(part, diagnostic.part)
        assertTrue(diagnostic.sourceContext?.path.orEmpty().endsWith("/w:vMerge[1]"))
    }

    private fun positions(border: Int, continuationFloor: Int? = null): Map<String, NativeWordFeatureSupport.Position> {
        val support = NativeWordFeatureSupport(temporary)
        return Loader.loadPDF(support.generate(document(support, border, continuationFloor, 8), "standard")).use { pdf ->
            assertEquals(1, pdf.numberOfPages)
            support.positions(pdf, Regex("ORIGIN|AFTER"))
        }
    }

    private fun document(support: NativeWordFeatureSupport, border: Int, continuationFloor: Int?, right: Int): ByteArray {
        val parts = PdfFixture.unzip(support.bytes(source)).toMutableMap()
        parts[part] = fixture(parts.getValue(part).decodeToString(), border, continuationFloor, right).toByteArray()
        val settings = parts.getValue("word/settings.xml").decodeToString()
        val mode = """<w:compatSetting w:name="compatibilityMode" w:uri="http://schemas.microsoft.com/office/word" w:val="15"/>"""
        parts["word/settings.xml"] = (if ("</w:compat>" in settings) settings.replace("</w:compat>", "$mode</w:compat>")
            else settings.replace("</w:settings>", "<w:compat>$mode</w:compat></w:settings>")).toByteArray()
        return PdfFixture.zip(parts)
    }

    private fun fixture(xml: String, border: Int, floor: Int?, right: Int): String {
        val area = metric(xml, "pgSz", "w") - metric(xml, "pgMar", "left") - metric(xml, "pgMar", "right")
        val minimum = floor?.let { """<w:trPr><w:trHeight w:val="$it" w:hRule="atLeast"/></w:trPr>""" }.orEmpty()
        val table = """<w:tbl><w:tblPr><w:tblW w:type="pct" w:w="5000"/><w:tblLayout w:type="autofit"/>""" +
            """<w:tblCellMar><w:top w:w="0" w:type="dxa"/><w:left w:w="108" w:type="dxa"/>""" +
            """<w:bottom w:w="0" w:type="dxa"/><w:right w:w="108" w:type="dxa"/></w:tblCellMar></w:tblPr>""" +
            """<w:tblGrid><w:gridCol w:w="$area"/></w:tblGrid><w:tr>""" +
            cell(true, 8, 8, "ORIGIN") + "</w:tr><w:tr>" + minimum + cell(false, border, right, "") + "</w:tr></w:tbl>"
        return xml.substringBefore("<w:body>") + "<w:body>$table${paragraph("AFTER")}<w:sectPr>" + xml.substringAfter("<w:sectPr>")
    }

    private fun metric(xml: String, element: String, attribute: String): Int {
        val nodes = requireNotNull(Regex("""<w:$element\b[^>]*/>""").find(xml)).value
        return requireNotNull(Regex("""w:$attribute="([0-9]+)"""").find(nodes)).groupValues[1].toInt()
    }

    private fun cell(first: Boolean, top: Int, right: Int, text: String): String {
        val merge = if (first) """<w:vMerge w:val="restart"/>""" else "<w:vMerge/>"
        val borders = listOf("top" to top, "left" to 8, "bottom" to 8, "right" to right).joinToString("") { (edge, width) ->
            """<w:$edge w:val="single" w:sz="$width" w:color="03A783"/>"""
        }
        return """<w:tc><w:tcPr><w:tcW w:type="pct" w:w="5000"/>$merge<w:tcBorders>$borders</w:tcBorders></w:tcPr>""" +
            paragraph(text) + "</w:tc>"
    }

    private fun paragraph(text: String): String {
        val run = if (text.isEmpty()) "" else "<w:r><w:t>$text</w:t></w:r>"
        return """<w:p><w:pPr><w:spacing w:before="0" w:after="0" w:line="280" w:lineRule="exact"/></w:pPr>$run</w:p>"""
    }

    private companion object {
        const val source = "/floating-drawings/control/a4/source.docx"
        const val part = "word/document.xml"
    }
}
