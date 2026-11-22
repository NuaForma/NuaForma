package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordHeaderWrapContourTest {
    private val wp = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    private val xml = ConversionXml(GenerationContext())
    private val rectangle = """<wp:wrapPolygon edited="0"><wp:start x="1000" y="1200"/>
        <wp:lineTo x="1000" y="4800"/><wp:lineTo x="15000" y="4800"/>
        <wp:lineTo x="15000" y="1200"/><wp:lineTo x="1000" y="1200"/></wp:wrapPolygon>"""

    private fun read(content: String, side: String = "bothSides"): XmlElement =
        xml.read("""<wp:wrapTight xmlns:wp="$wp" wrapText="$side">$content</wp:wrapTight>""".toByteArray())

    private fun bottom(markup: XmlElement): Float = WordHeaderWrapContour.bottom(markup) { _, message ->
        throw IllegalArgumentException(message)
    }

    @Test
    fun measuredContourUsesTheImageCoordinateScale() {
        assertEquals(4800f / 21600f, bottom(read(rectangle)), .0001f)
    }

    @Test
    fun incorrectNamespaceGeometryAndScopeAreRejected() {
        for (markup in listOf(read(rectangle, "left"),
            read(rectangle.replace("4800", "21601")),
            read(rectangle.replace("""<wp:lineTo x="1000" y="1200"/>""", "")),
            read(rectangle.replace("""edited="0"""", """edited="other"""")),
            read(rectangle.replace("""<wp:lineTo x="15000" y="4800"/>""", """<wp:start x="15000" y="4800"/>""")))) {
            assertThrows(IllegalArgumentException::class.java) { bottom(markup) }
        }
    }
}
