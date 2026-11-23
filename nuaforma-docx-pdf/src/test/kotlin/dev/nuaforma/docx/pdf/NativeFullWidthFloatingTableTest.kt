package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.contracts.GenerationException
import dev.nuaforma.docx.pdf.adapters.nativeengine.NativeDocxReadException
import dev.nuaforma.docx.pdf.adapters.nativeengine.NativeDocxReader
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import dev.nuaforma.pdf.PdfTable
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Path

class NativeFullWidthFloatingTableTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `the qualified full width text-relative table follows body flow`() {
        val support = NativeWordFeatureSupport(temporary)
        for (automatic in listOf(false, true)) {
            val inline = support.generate(template(support, floating = false, full = true, automatic = automatic), "standard")
            val positioned = support.generate(template(support, floating = true, full = true, automatic = automatic), "standard")
            Loader.loadPDF(inline).use { expected ->
                Loader.loadPDF(positioned).use { actual ->
                    assertEquals(expected.numberOfPages, actual.numberOfPages)
                    for (page in 0 until expected.numberOfPages) {
                        assertImagesMatch(PDFRenderer(expected).renderImageWithDPI(page, 96f),
                            PDFRenderer(actual).renderImageWithDPI(page, 96f)) {
                            pixelTolerance = 0.0
                            allowedDifferentPixelsPercent = 0.0
                            threshold = 1
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `a narrow floating table still needs actual text wrapping support`() {
        val support = NativeWordFeatureSupport(temporary)
        val output = ByteArrayOutputStream()
        val failure = assertThrows(GenerationException::class.java) {
            support.generate(template(support, floating = true, full = false), "standard", output)
        }
        assertEquals(0, output.size())
        val diagnostic = failure.diagnostics.single()
        assertEquals("word/document.xml", diagnostic.part)
        assertTrue(diagnostic.sourceContext?.path.orEmpty().contains("tblpPr"))
    }

    @Test
    fun `text-relative tables preserve signed position bounds and identify incompatible overlap policies`() {
        val support = NativeWordFeatureSupport(temporary)
        val source = template(support, floating = true, full = true)
        val parts = PdfFixture.unzip(source)
        val part = "word/document.xml"
        val xml = parts.getValue(part).decodeToString()
        val anchor = requireNotNull(Regex("""<w:tblpPr\b[^>]*/>""").find(xml)).value

        fun read(document: String) = NativeDocxReader(GenerationContext()) { _, _, _ -> "regular" }
            .read(PdfFixture.zip(parts + (part to document.toByteArray())))

        val margin = anchor.replace("w:vertAnchor=", "w:horzAnchor=\"margin\" w:vertAnchor=")
        for ((raw, expected) in listOf("0" to 0f, "1" to 0f, "41" to 2f, "32767" to 1638.3f)) {
            val table = read(xml.replace(anchor, margin.replace("tblpY=\"1\"", "tblpY=\"$raw\""))).blocks.filterIsInstance<PdfTable>().single()
            assertEquals(expected, table.textRelativeTopOffset)
        }
        assertEquals(0f, read(xml.replace(anchor, margin).replace("<w:tblOverlap w:val=\"never\"/>", ""))
            .blocks.filterIsInstance<PdfTable>().single().textRelativeTopOffset)

        fun rejected(document: String, reason: String) {
            val problem = assertThrows(NativeDocxReadException::class.java) { read(document) }
            assertEquals(part, problem.part)
            assertEquals(reason, problem.message)
        }

        for (raw in listOf("-1", "32768", "1.5", "2147483648")) {
            rejected(xml.replace(anchor, margin.replace("tblpY=\"1\"", "tblpY=\"$raw\"")), "Invalid text-relative table top offset")
        }
        rejected(xml.replace(anchor, margin.replace(" w:tblpY=\"1\"", "")), "Invalid text-relative table top offset")
        rejected(xml.replace(anchor, ""), "A table overlap rule requires a qualified position")
        rejected(xml.replace("<w:tblOverlap w:val=\"never\"/>", ""), "A full-width text-relative table needs a nonoverlap rule")
        rejected(xml.replace(anchor, margin).replace("w:val=\"never\"", "w:val=\"overlap\""), "Overlapping floating tables are not qualified")
        rejected(xml.replace("w:val=\"never\"", "w:val=\"overlap\""), "Only the qualified nonoverlapping text-relative table position is supported")
        rejected(xml.replace(anchor, margin.replace("180", "181")), "The text-relative margin anchor has unsupported side clearance")
        rejected(xml.replace("w:rightFromText=\"180\"", "w:rightFromText=\"181\""), "Only a qualified text-relative table position is supported")
        rejected(xml.replace("w:vertAnchor=\"text\"", "w:vertAnchor=\"page\""), "Only a qualified text-relative table position is supported")
        rejected(xml.replace(anchor, margin.replace("horzAnchor=\"margin\"", "horzAnchor=\"page\"")), "Unsupported horizontal floating table reference")
    }

    private fun template(support: NativeWordFeatureSupport, floating: Boolean, full: Boolean, automatic: Boolean = false): ByteArray {
        val parts = PdfFixture.unzip(support.bytes(source)).toMutableMap()
        val part = "word/document.xml"
        val document = parts.getValue(part).decodeToString()

        fun metric(element: String, attribute: String): Int {
            val properties = requireNotNull(Regex("""<w:$element\b[^>]*/>""").find(document)).value
            val value = requireNotNull(Regex("""w:$attribute="([0-9]+)"""").find(properties)).groupValues[1]
            return value.toInt()
        }

        val area = metric("pgSz", "w") - metric("pgMar", "left") - metric("pgMar", "right")
        val width = if (full) area - if (automatic) 20 else 4 else 5200
        val left = width / 2
        val right = width - left
        val clearance = if (automatic) 187 else 180
        val anchor = if (floating) {
            """<w:tblpPr w:leftFromText="$clearance" w:rightFromText="$clearance" w:vertAnchor="text" w:tblpY="1"/><w:tblOverlap w:val="never"/>"""
        } else ""
        val preferred = if (automatic) """<w:tblW w:w="5000" w:type="pct"/>""" else """<w:tblW w:w="$width" w:type="dxa"/>"""
        val layout = if (automatic) "autofit" else "fixed"
        val structure = """<w:tbl><w:tblPr>$anchor$preferred<w:tblInd w:w="0" w:type="dxa"/>""" +
            """<w:tblLayout w:type="$layout"/><w:tblCellMar><w:top w:w="0" w:type="dxa"/><w:left w:w="108" w:type="dxa"/>""" +
            """<w:bottom w:w="0" w:type="dxa"/><w:right w:w="108" w:type="dxa"/></w:tblCellMar></w:tblPr>""" +
            """<w:tblGrid><w:gridCol w:w="$left"/><w:gridCol w:w="$right"/></w:tblGrid><w:tr>""" +
            cell(left, "FIRST_CELL") + cell(right, "SECOND_CELL") + "</w:tr></w:tbl>"
        val body = paragraph("BEFORE_TABLE") + paragraph("") + structure + paragraph("AFTER_TABLE")
        parts[part] = (document.substringBefore("<w:body>") + "<w:body>$body<w:sectPr>" + document.substringAfter("<w:sectPr>")).toByteArray()

        val settingsPart = "word/settings.xml"
        val settings = parts.getValue(settingsPart).decodeToString()
        val mode = """<w:compatSetting w:name="compatibilityMode" w:uri="http://schemas.microsoft.com/office/word" w:val="15"/>"""
        parts[settingsPart] = (if ("</w:compat>" in settings) settings.replace("</w:compat>", "$mode</w:compat>")
            else settings.replace("</w:settings>", "<w:compat>$mode</w:compat></w:settings>")).toByteArray()
        return PdfFixture.zip(parts)
    }

    private fun paragraph(text: String) =
        """<w:p><w:pPr><w:spacing w:before="0" w:after="0" w:line="280" w:lineRule="exact"/></w:pPr>""" +
            "<w:r><w:t>$text</w:t></w:r></w:p>"

    private fun cell(width: Int, text: String) =
        """<w:tc><w:tcPr><w:tcW w:type="dxa" w:w="$width"/></w:tcPr>""" + paragraph(text) + "</w:tc>"

    private companion object {
        const val source = "/floating-drawings/control/a4/source.docx"
    }
}
