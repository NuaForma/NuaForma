package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.GenerationException

import org.apache.pdfbox.Loader
import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.cos.COSNumber
import org.apache.pdfbox.pdfparser.PDFStreamParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.math.roundToInt

class NativeColoredUnderlineTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `ink and underline colors stay independent and none paints no underline`() {
        val support = NativeWordFeatureSupport(temporary)
        val lines = line("VISIBLE", "single", "7030A0") + line("AUTOMATIC", "single", "auto") +
            line("CANCELLED", "none", "FF0000") + line("HEAVIER", "thick", "FFC000")
        Loader.loadPDF(support.generate(template(support, lines), "standard")).use { pdf ->
            val glyphColors = mutableSetOf<Int>()
            val rectangleHeights = mutableMapOf<Int, Float>()
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
                    "re" -> rectangleHeights[color] = operands.last().floatValue()
                }
                operands.clear()
            }
            assertEquals(setOf(0x274060), glyphColors)
            assertTrue(rectangleHeights.keys.containsAll(setOf(0x7030A0, 0x274060, 0xFFC000)))
            assertTrue(0xFF0000 !in rectangleHeights)
            assertTrue(rectangleHeights.getValue(0xFFC000) > rectangleHeights.getValue(0x7030A0))
        }
    }

    @Test
    fun `untested sizes and other underline patterns retain a source diagnostic`() {
        val support = NativeWordFeatureSupport(temporary)
        for ((mode, size) in listOf("thick" to 56, "double" to 28)) {
            val failure = assertThrows(GenerationException::class.java) {
                support.generate(template(support, line("VISIBLE", mode, "7030A0", size)), "standard")
            }
            assertEquals("word/document.xml", failure.diagnostics.single().part)
        }
    }

    private fun template(support: NativeWordFeatureSupport, lines: String): ByteArray {
        val parts = PdfFixture.unzip(support.bytes("/floating-drawings/control/a4/source.docx")).toMutableMap()
        val part = "word/document.xml"
        val source = parts.getValue(part).decodeToString()
        parts[part] = (source.substringBefore("<w:body>") + "<w:body>$lines<w:sectPr>" + source.substringAfter("<w:sectPr>")).toByteArray()
        return PdfFixture.zip(parts)
    }

    private fun line(text: String, mode: String, color: String, size: Int = 28) =
        """<w:p><w:r><w:rPr><w:sz w:val="$size"/><w:color w:val="274060"/><w:u w:val="$mode" w:color="$color"/>""" +
            """</w:rPr><w:t>$text</w:t></w:r></w:p>"""
}
