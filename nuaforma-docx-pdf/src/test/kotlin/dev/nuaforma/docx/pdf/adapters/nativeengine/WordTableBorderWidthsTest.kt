package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.pdf.PdfBorder
import dev.nuaforma.pdf.PdfColor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class WordTableBorderWidthsTest {
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val source = ConversionXml(GenerationContext())
    private val reject: (XmlElement, String) -> Nothing = { _, message -> throw IllegalArgumentException(message) }

    @Test
    fun `the six point limit preserves valid solid widths and rejects wider table strokes`() {
        val colors = WordThemeColors(null, reject)
        fun read(units: Int) = WordTableBorders.read(source.read(
            """<w:top xmlns:w="$word" w:val="single" w:sz="$units" w:color="03A783"/>""".toByteArray()), colors, reject)
        val color = PdfColor(3, 167, 131)
        assertEquals(PdfBorder(.5f, color), read(4))
        assertEquals(PdfBorder(4f, color), read(32))
        assertEquals(PdfBorder(6f, color), read(48))
        assertThrows(IllegalArgumentException::class.java) { read(49) }
        assertThrows(IllegalArgumentException::class.java) { PdfBorder(6.125f, color) }
    }
}
