package dev.nuaforma.docx.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path

class NativeCompoundParagraphBorderTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @CsvSource("thinThickSmallGap,12,3", "thickThinSmallGap,3,12")
    fun keepsTheBandOrderOnEveryPageEdge(style: String, firstWidth: Int, secondWidth: Int) {
        val support = NativeWordFeatureSupport(temporary)
        val pdf = Loader.loadPDF(support.generate(source(style), "standard"))
        pdf.use {
            assertEquals(1, it.numberOfPages)
            val raster = PDFRenderer(it).renderImageWithDPI(0, 288f)
            fun expect(actual: List<IntRange>, first: Int, second: Int) {
                assertEquals(2, actual.size, actual.toString())
                assertTrue(actual[0].count() in first - 1..first + 1, actual.toString())
                assertTrue(actual[1].count() in second - 1..second + 1, actual.toString())
                assertTrue(actual[1].first - actual[0].last - 1 in 2..4, actual.toString())
            }
            val top = detectedBands(raster.height) { y -> raster.getRGB(600, y) and 0xFFFFFF == 0x980026 }
            val bottom = detectedBands(raster.height) { y -> raster.getRGB(600, y) and 0xFFFFFF == 0x214D30 }
            expect(top, firstWidth, secondWidth)
            expect(bottom, firstWidth, secondWidth)
            val insideY = (top.last().last + bottom.first().first) / 2
            val left = detectedBands(raster.width) { x -> raster.getRGB(x, insideY) and 0xFFFFFF == 0x7851A9 }
            val right = detectedBands(raster.width) { x -> raster.getRGB(x, insideY) and 0xFFFFFF == 0x007A99 }
            expect(left, firstWidth, secondWidth)
            expect(right, firstWidth, secondWidth)
        }
    }

    private fun detectedBands(size: Int, matches: (Int) -> Boolean): List<IntRange> {
        val bands = mutableListOf<IntRange>()
        var start = -1
        for (index in 0..size) {
            if (index < size && matches(index)) {
                if (start < 0) start = index
            } else if (start >= 0) {
                bands += start until index
                start = -1
            }
        }
        return bands
    }

    private fun source(style: String): ByteArray {
        val parts = PdfFixture.parts()
        val colors = linkedMapOf("top" to "980026", "left" to "7851A9", "bottom" to "214D30", "right" to "007A99")
        val borders = colors.entries.joinToString("") { (edge, color) ->
            """<w:$edge w:val="$style" w:sz="24" w:space="1" w:color="$color"/>"""
        }
        parts["word/styles.xml"] = parts.getValue("word/styles.xml").decodeToString()
            .replace("DejaVu Sans", "Liberation Sans").toByteArray()
        parts["word/document.xml"] = ("""<w:document xmlns:w="WORD_NAMESPACE"><w:body><w:p>""" +
            """<w:pPr><w:pBdr>$borders</w:pBdr><w:spacing w:line="400" w:lineRule="exact"/></w:pPr>""" +
            """<w:r><w:t>Compound border</w:t></w:r></w:p><w:sectPr><w:pgSz w:w="12240" w:h="15840"/>""" +
            """<w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>""")
            .replace("WORD_NAMESPACE", PdfFixture.W).toByteArray()
        return PdfFixture.zip(parts)
    }
}
