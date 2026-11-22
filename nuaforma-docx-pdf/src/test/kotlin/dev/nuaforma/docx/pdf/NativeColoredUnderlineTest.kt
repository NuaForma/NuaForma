package dev.nuaforma.docx.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.cos.COSNumber
import org.apache.pdfbox.pdfparser.PDFStreamParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.math.roundToInt

class NativeColoredUnderlineTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `ink and underline colors stay independent and none paints no underline`() {
        val support = NativeWordFeatureSupport(temporary)
        val parts = PdfFixture.unzip(support.bytes("/floating-drawings/control/a4/source.docx")).toMutableMap()
        val part = "word/document.xml"
        val source = parts.getValue(part).decodeToString()
        val lines = line("VISIBLE", "single", "7030A0") + line("AUTOMATIC", "single", "auto") + line("CANCELLED", "none", "FF0000")
        parts[part] = (source.substringBefore("<w:body>") + "<w:body>$lines<w:sectPr>" + source.substringAfter("<w:sectPr>")).toByteArray()
        Loader.loadPDF(support.generate(PdfFixture.zip(parts), "standard")).use { pdf ->
            val glyphColors = mutableSetOf<Int>()
            val rectangleColors = mutableSetOf<Int>()
            val colors = mutableListOf<Int>()
            val operands = mutableListOf<COSNumber>()
            var color = 0
            for (token in PDFStreamParser(pdf.getPage(0)).parse()) {
                if (token is COSNumber) operands += token
                if (token !is Operator) continue
                when (token.name) {
                    "rg" -> color = operands.takeLast(3).fold(0) { rgb, channel ->
                        (rgb shl 8) or (255 * channel.floatValue()).roundToInt()
                    }
                    "q" -> colors += color
                    "Q" -> color = colors.removeLast()
                    "Tj", "TJ" -> glyphColors += color
                    "re" -> rectangleColors += color
                }
                operands.clear()
            }
            assertEquals(setOf(0x274060), glyphColors)
            assertTrue(rectangleColors.containsAll(setOf(0x7030A0, 0x274060)))
            assertTrue(0xFF0000 !in rectangleColors)
        }
    }

    private fun line(text: String, mode: String, color: String) =
        """<w:p><w:r><w:rPr><w:color w:val="274060"/><w:u w:val="$mode" w:color="$color"/></w:rPr><w:t>$text</w:t></w:r></w:p>"""
}
