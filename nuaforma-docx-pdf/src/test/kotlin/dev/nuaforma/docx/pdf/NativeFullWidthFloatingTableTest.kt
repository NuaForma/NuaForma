package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.GenerationException
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
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
        val inline = support.generate(template(support, floating = false, full = true), "standard")
        val positioned = support.generate(template(support, floating = true, full = true), "standard")
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

    private fun template(support: NativeWordFeatureSupport, floating: Boolean, full: Boolean): ByteArray {
        val parts = PdfFixture.unzip(support.bytes(source)).toMutableMap()
        val part = "word/document.xml"
        val document = parts.getValue(part).decodeToString()

        fun metric(element: String, attribute: String): Int {
            val properties = requireNotNull(Regex("""<w:$element\b[^>]*/>""").find(document)).value
            val value = requireNotNull(Regex("""w:$attribute="([0-9]+)"""").find(properties)).groupValues[1]
            return value.toInt()
        }

        val width = if (full) metric("pgSz", "w") - metric("pgMar", "left") - metric("pgMar", "right") - 4 else 5200
        val left = width / 2
        val right = width - left
        val anchor = if (floating) {
            """<w:tblpPr w:leftFromText="180" w:rightFromText="180" w:vertAnchor="text" w:tblpY="1"/><w:tblOverlap w:val="never"/>"""
        } else ""
        val structure = """<w:tbl><w:tblPr>$anchor<w:tblW w:w="$width" w:type="dxa"/><w:tblInd w:w="0" w:type="dxa"/>""" +
            """<w:tblLayout w:type="fixed"/><w:tblCellMar><w:top w:w="0" w:type="dxa"/><w:left w:w="108" w:type="dxa"/>""" +
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
