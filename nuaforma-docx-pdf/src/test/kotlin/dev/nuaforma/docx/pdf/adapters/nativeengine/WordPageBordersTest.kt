package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.pdf.PdfBorder
import dev.nuaforma.pdf.PdfColor
import dev.nuaforma.pdf.PdfMargins
import dev.nuaforma.pdf.PdfPage
import dev.nuaforma.pdf.PdfPageFrameDisplay
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordPageBordersTest {
    private val xml = ConversionXml(GenerationContext())
    private val page = PdfPage(612f, 792f, PdfMargins(72f, 72f, 72f, 72f))
    private val edges = listOf("top" to 24, "right" to 6, "bottom" to 12, "left" to 18).joinToString("") { (side, distance) ->
        """<w:$side w:val="single" w:color="7F2149" w:sz="4" w:space="$distance"/>"""
    }

    private fun frame(properties: String = """w:offsetFrom="page"""", sides: String = edges) =
        xml.read("""<w:pgBorders xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" $properties>$sides</w:pgBorders>""".toByteArray())

    private fun reject(node: XmlElement, message: String): Nothing = throw NativeDocxReadException(message, "word/document.xml")

    @Test
    fun `uniform edges produce a physical frame with the same source offsets`() {
        for ((display, expected) in listOf("allPages" to PdfPageFrameDisplay.ALL,
            "firstPage" to PdfPageFrameDisplay.FIRST, "notFirstPage" to PdfPageFrameDisplay.EXCEPT_FIRST)) {
            val source = frame("""w:offsetFrom="page" w:display="$display" w:zOrder="front"""")
            val original = xml.write(source)
            val result = WordPageBorders.read(source, page, ::reject)
            assertEquals(expected, result.display)
            assertEquals(PdfMargins(24f, 6f, 12f, 18f), result.offsets)
            assertEquals(PdfBorder(.5f, PdfColor(127, 33, 73)), result.border)
            assertArrayEquals(original, xml.write(source))
        }
    }

    @Test
    fun `original section validation rejects invalid border attributes before binding`() {
        val valid = """<w:pgBorders w:offsetFrom="page">$edges</w:pgBorders>"""
        for (border in listOf(valid + valid, valid.replace("w:sz=", """w:shadow="1" w:sz="""),
            valid.replace("<w:pgBorders", "<other:pgBorders").replace("</w:pgBorders>", "</other:pgBorders>"))) {
            val source = xml.read("""<w:sectPr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                xmlns:other="urn:foreign">$border</w:sectPr>""".toByteArray())
            assertThrows(NativeDocxReadException::class.java) { WordSectionMetadata.validateSource(source, ::reject) }
        }
    }

    @Test
    fun `source diagnostics identify frames with ambiguous corner geometry and unsupported positioning`() {
        val cases = listOf(frame("""w:offsetFrom="text""""), frame("""w:offsetFrom="page" w:zOrder="back""""),
            frame("""w:offsetFrom="page" w:display="everyOtherPage""""),
            frame(sides = edges.substringBefore("<w:left")),
            frame(sides = edges.replaceFirst("7F2149", "000000")),
            frame(sides = edges.replaceFirst("""w:val="single"""", """w:val="double"""")),
            frame(sides = edges.replaceFirst("""w:space="24"""", """w:space="32"""")))
        for (source in cases) assertThrows(NativeDocxReadException::class.java) { WordPageBorders.read(source, page, ::reject) }
    }
}
