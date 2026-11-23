package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class WordInvisiblePictureOutlineTest {
    @Test
    fun `an invisible picture outline accepts only the qualified default decorations`() {
        val implicitEnds = """<a:ln w="9525"><a:noFill/><a:miter lim="800000"/><a:headEnd/><a:tailEnd/></a:ln>"""
        val explicitEnds = """<a:ln><a:noFill/><a:headEnd type="none" w="med" len="med"/></a:ln>"""
        for (line in listOf(implicitEnds, explicitEnds, "<a:ln><a:noFill/></a:ln>")) {
            val root = picture(line)
            WordPictureMetadata.normalize(root, ::reject)
            assertEquals(listOf("prstGeom"), properties(root).elements().map { it.name.localPart })
        }

        val visible = picture("""<a:ln><a:solidFill><a:srgbClr val="AA2211"/></a:solidFill></a:ln>""")
        val original = properties(visible).elements().last()
        WordPictureMetadata.normalize(visible, ::reject)
        assertSame(original, properties(visible).elements().last())

        for (line in listOf(implicitEnds.replace("<a:headEnd/>", """<a:headEnd type="triangle"/>"""),
            implicitEnds.replace("800000", "0"), implicitEnds.replace("<a:tailEnd/>", "<a:tailEnd>text</a:tailEnd>"),
            implicitEnds.replace("<a:noFill/>", "<a:noFill/><a:noFill/>"))) {
            assertThrows(NativeDocxReadException::class.java) { WordPictureMetadata.normalize(picture(line), ::reject) }
        }
    }

    private fun picture(line: String) = ConversionXml(GenerationContext()).read(
        ("""<wp:inline xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" """ +
            """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">""" +
            """<a:graphic><pic:pic><pic:spPr><a:prstGeom prst="rect"/>$line</pic:spPr></pic:pic></a:graphic></wp:inline>""").toByteArray()
    )

    private fun properties(root: XmlElement) = root.descendants().single { it.name.localPart == "spPr" }

    private fun reject(node: XmlElement, message: String): Nothing =
        throw NativeDocxReadException("${node.name.localPart} $message", "word/document.xml")
}
