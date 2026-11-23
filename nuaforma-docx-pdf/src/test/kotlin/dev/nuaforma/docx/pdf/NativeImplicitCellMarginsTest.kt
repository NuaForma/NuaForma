package dev.nuaforma.docx.pdf

import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class NativeImplicitCellMarginsTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `the default horizontal clearance is half a point or half of a wider uniform border`() {
        val support = NativeWordFeatureSupport(temporary)
        val original = PdfFixture.unzip(support.bytes(source))
        val base = original.getValue(part).decodeToString()
        val originalStyles = original.filterKeys { it in styleParts }.mapValues { (_, value) ->
            withoutTableMargins(value.decodeToString()).toByteArray()
        }

        val settingsPart = "word/settings.xml"
        val originalSettings = original.getValue(settingsPart).decodeToString()
        val mode = """<w:compatSetting w:name="compatibilityMode" w:uri="http://schemas.microsoft.com/office/word" w:val="15"/>"""
        val settings = if ("</w:compat>" in originalSettings) {
            originalSettings.replace("</w:compat>", "$mode</w:compat>")
        } else {
            originalSettings.replace("</w:settings>", "<w:compat>$mode</w:compat></w:settings>")
        }

        fun image(padding: Int?, stroke: Int, custom: String? = null) = Loader.loadPDF(support.generate(
            PdfFixture.zip(original + originalStyles + (settingsPart to settings.toByteArray()) +
                (part to fixture(base, padding, stroke, custom).toByteArray())), "standard",
        )).use { PDFRenderer(it).renderImageWithDPI(0, 96f) }

        for ((stroke, padding) in listOf(0 to 10, 4 to 10, 8 to 10, 16 to 20)) {
            assertImagesMatch(image(padding, stroke), image(null, stroke)) {
                pixelTolerance = 0.0
                allowedDifferentPixelsPercent = 0.0
                threshold = 1
            }
        }

        val borderless = image(null, 0)
        for (kind in listOf("none", "nil")) {
            assertImagesMatch(borderless, image(null, 16, kind)) {
                pixelTolerance = 0.0
                allowedDifferentPixelsPercent = 0.0
                threshold = 1
            }
        }
    }

    private fun fixture(xml: String, padding: Int?, stroke: Int, custom: String?): String {
        val margins = padding?.let {
            """<w:tblCellMar><w:top w:w="0" w:type="dxa"/><w:left w:w="$it" w:type="dxa"/>""" +
                """<w:bottom w:w="0" w:type="dxa"/><w:right w:w="$it" w:type="dxa"/></w:tblCellMar>"""
        }.orEmpty()
        val rules = listOf("top", "left", "bottom", "right", "insideH", "insideV").joinToString("") { name ->
            if (stroke == 0) """<w:$name w:val="nil"/>""" else """<w:$name w:val="single" w:sz="$stroke" w:color="000000"/>"""
        }
        val customEdges = custom?.let { kind ->
            listOf("top", "left", "bottom", "right").joinToString("", "<w:tcBorders>", "</w:tcBorders>") { side ->
                "<w:$side w:val=\"$kind\"/>"
            }
        }.orEmpty()
        val table = """<w:tbl><w:tblPr><w:tblW w:w="5200" w:type="dxa"/><w:tblBorders>$rules</w:tblBorders>$margins""" +
            """<w:tblLayout w:type="fixed"/></w:tblPr><w:tblGrid><w:gridCol w:w="2600"/><w:gridCol w:w="2600"/></w:tblGrid>""" +
            "<w:tr>" + cell("DEFAULT_LEFT", "left", customEdges) + cell("DEFAULT_RIGHT", "right", customEdges) + "</w:tr></w:tbl>"
        return xml.substringBefore("<w:body>") + "<w:body>$table<w:sectPr>" + xml.substringAfter("<w:sectPr>")
    }

    private fun cell(text: String, alignment: String, customEdges: String): String =
        """<w:tc><w:tcPr><w:tcW w:w="2600" w:type="dxa"/>$customEdges</w:tcPr><w:p><w:pPr><w:jc w:val="$alignment"/>""" +
            """<w:spacing w:before="0" w:after="0" w:line="280" w:lineRule="exact"/></w:pPr><w:r><w:t>$text</w:t></w:r></w:p></w:tc>"""

    private fun withoutTableMargins(xml: String) = xml.replace(Regex("<w:tblCellMar>.*?</w:tblCellMar>", RegexOption.DOT_MATCHES_ALL), "")

    private companion object {
        const val source = "/floating-drawings/control/a4/source.docx"
        const val part = "word/document.xml"
        val styleParts = setOf("word/styles.xml", "word/stylesWithEffects.xml")
    }
}
