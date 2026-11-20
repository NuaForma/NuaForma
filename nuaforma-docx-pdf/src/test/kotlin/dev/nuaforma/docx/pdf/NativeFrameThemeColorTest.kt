package dev.nuaforma.docx.pdf

import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.awt.Color
import java.nio.file.Path

class NativeFrameThemeColorTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `theme frame paint matches the RGB colors measured in Word`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        val parts = PdfFixture.unzip(support.bytes("/floating-drawings/control/$paper/source.docx")).toMutableMap()
        val document = parts.getValue(part).decodeToString()
        val themePart = "word/theme/theme1.xml"
        parts[themePart] = PdfFixture.unzip(support.bytes("/theme-colors/a4/source.docx")).getValue(themePart).decodeToString()
            .replace("val=\"D6E2EF\"", "val=\"FFFFFF\"")
            .replace("val=\"C0504D\"", "val=\"663399\"").toByteArray()
        update(parts, "[Content_Types].xml", "</Types>",
            """<Override PartName="/$themePart" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/></Types>""")
        update(parts, "word/_rels/document.xml.rels", "</Relationships>",
            """<Relationship Id="theme" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="theme/theme1.xml"/></Relationships>""")

        fun render(fill: String, stroke: String): java.awt.image.BufferedImage {
            parts[part] = document.replace(fillColor, fill).replace(strokeColor, stroke).toByteArray()
            return Loader.loadPDF(support.generate(PdfFixture.zip(parts), "standard")).use { pdf ->
                assertEquals(1, pdf.numberOfPages)
                PDFRenderer(pdf).renderImageWithDPI(0, 96f)
            }
        }

        val gray = render("""<a:schemeClr val="bg1"><a:lumMod val="95000"/></a:schemeClr>""", """<a:schemeClr val="accent5"/>""")
        val purple = render("""<a:schemeClr val="accent2"><a:lumMod val="85000"/></a:schemeClr>""", """<a:schemeClr val="lt1"/>""")
        assertEquals(Color(242, 242, 242).rgb, gray.getRGB(170, 190))
        assertEquals(Color(87, 43, 130).rgb, purple.getRGB(170, 190))
        assertImagesMatch(render("""<a:srgbClr val="F2F2F2"/>""", """<a:srgbClr val="ED7D31"/>"""), gray) {
            pixelTolerance = 0.0
            allowedDifferentPixelsPercent = 0.0
            threshold = 1
        }
        assertImagesMatch(render("""<a:srgbClr val="572B82"/>""", """<a:srgbClr val="FFFFFF"/>"""), purple) {
            pixelTolerance = 0.0
            allowedDifferentPixelsPercent = 0.0
            threshold = 1
        }
    }

    @Test
    fun `missing palette and invalid or active transforms produce original diagnostics`() {
        val support = NativeWordFeatureSupport(temporary)
        val source = "/floating-drawings/control/a4/source.docx"
        for ((element, color) in listOf(
            "schemeClr" to """<a:schemeClr val="accent1"/>""",
            "schemeClr" to """<a:schemeClr val="phClr"/>""",
            "lumMod" to """<a:schemeClr val="accent1"><a:lumMod val="100001"/></a:schemeClr>""",
            "lumMod" to """<a:schemeClr val="accent1"><a:lumMod val="100"/><a:lumMod val="100"/></a:schemeClr>""",
            "alpha" to """<a:schemeClr val="accent1"><a:alpha val="20000"/></a:schemeClr>""",
        )) support.rejectChanged(source, part, element) { it.replaceFirst(fillColor, color) }
    }

    private fun update(parts: MutableMap<String, ByteArray>, key: String, old: String, new: String) {
        parts[key] = parts.getValue(key).decodeToString().replace(old, new).toByteArray()
    }

    private companion object {
        const val part = "word/document.xml"
        const val fillColor = """<a:srgbClr val="FF0000"/>"""
        const val strokeColor = """<a:srgbClr val="008B90"/>"""
    }
}
