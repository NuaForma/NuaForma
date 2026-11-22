package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.pdf.PdfTextBoxAutoFit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordFrameTextMetadataTest {
    private val context = GenerationContext()
    private val xml = ConversionXml(context)
    private val fit = "<a:spAutoFit/>"
    private val warp = """<a:prstTxWarp prst="textNoShape"><a:avLst/></a:prstTxWarp>"""

    private fun read(body: String) = xml.read("""
        <root xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape"
          xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:other="urn:other">$body</root>
        """.trimIndent().toByteArray())

    private fun body(wrap: String = "square", attributes: String = "", children: String = fit) =
        """<wps:bodyPr rot="0" vert="horz" wrap="$wrap" $attributes>$children</wps:bodyPr>"""

    private fun reject(node: XmlElement, message: String): Nothing =
        throw IllegalArgumentException("${node.name.localPart}: $message")

    private fun validate(body: String, unrotated: Boolean = true) =
        WordFrameTextMetadata.validate(read(body).elements().single(), unrotated, ::reject)

    @Test
    fun `a horizontal frame preserves its qualified autofit mode and optional unwarped preset`() {
        assertEquals(PdfTextBoxAutoFit.HEIGHT, validate(body()))
        assertEquals(PdfTextBoxAutoFit.WIDTH_AND_HEIGHT, validate(body("none", """upright="1" anchorCtr="0"
            numCol="1" vertOverflow="overflow" compatLnSpc="true"""", warp + fit)))
        assertNull(validate(body(children = "")))
        assertNull(validate(body(attributes = """upright="false"""", children = ""), unrotated = false))
    }

    @Test
    fun `autofit rejects incompatible rotation wrapping and editing flags`() {
        val cases = listOf(
            body(attributes = """other:upright="0"""") to "Invalid original frame text flag namespace",
            body(attributes = """anchorCtr="true"""") to "Unsupported frame bodyPr/@anchorCtr",
            body(attributes = """upright="yes"""") to "Invalid original frame bodyPr/@upright",
            body().replace("rot=\"0\"", "rot=\"1\"") to "Shape autofit requires unrotated horizontal text",
            body().replace("vert=\"horz\"", "vert=\"vert\"") to "Shape autofit requires unrotated horizontal text",
            body("invalid") to "Unsupported shape autofit text wrapping",
        )
        for ((element, reason) in cases) assertEquals("bodyPr: $reason",
            assertThrows(IllegalArgumentException::class.java) { validate(element) }.message)
        assertEquals("bodyPr: Upright text in rotated frames is not qualified",
            assertThrows(IllegalArgumentException::class.java) { validate(body(attributes = """upright="true""""), false) }.message)
        assertEquals("bodyPr: Shape autofit requires unrotated horizontal text",
            assertThrows(IllegalArgumentException::class.java) { validate(body(), false) }.message)
    }

    @Test
    fun `autofit rejects malformed fit declarations and text policies at the responsible element`() {
        val cases = listOf(
            body(children = fit + fit) to "spAutoFit: Duplicate shape autofit declaration",
            body(children = "<other:spAutoFit/>") to "spAutoFit: Invalid original shape autofit namespace",
            body(attributes = """other:numCol="1"""") to "bodyPr: Unsupported original shape autofit text policy",
            body(attributes = """numCol="2"""") to "bodyPr: Unsupported original shape autofit text policy",
            body(children = "visible" + fit) to "bodyPr: Unsupported original shape autofit text policy",
            body(children = """<a:spAutoFit val="1"/>""") to "spAutoFit: Invalid shape autofit properties",
            body(children = "<a:spAutoFit><a:avLst/></a:spAutoFit>") to "spAutoFit: Invalid shape autofit properties",
            body(children = "<a:spAutoFit>visible</a:spAutoFit>") to "spAutoFit: Invalid shape autofit properties",
            body(children = fit + warp + warp) to "prstTxWarp: Multiple additional shape autofit properties",
        )
        for ((element, diagnostic) in cases) assertEquals(diagnostic,
            assertThrows(IllegalArgumentException::class.java) { validate(element) }.message)
    }

    @Test
    fun `autofit does not accept warped text or unqualified adjustment lists`() {
        for (preset in listOf(warp.replace("a:prstTxWarp", "other:prstTxWarp"), warp.replace("prstTxWarp", "other"),
            warp.replace("prst=", "other:prst="), warp.replace("textNoShape", "textWave1"),
            warp.replace("<a:avLst/>", "visible<a:avLst/>"))) {
            assertTrue(assertThrows(IllegalArgumentException::class.java) { validate(body(children = fit + preset)) }
                .message!!.endsWith("Only an unwarped text preset is qualified for shape autofit"))
        }
        for (adjustments in listOf("", "<other:avLst/>", "<a:noFill/>", "<a:avLst/><a:avLst/>",
            "<a:avLst>visible</a:avLst>")) {
            assertEquals("prstTxWarp: Invalid unwarped autofit adjustment list", assertThrows(IllegalArgumentException::class.java) {
                validate(body(children = fit + warp.replace("<a:avLst/>", adjustments)))
            }.message)
        }
    }

    @Test
    fun `source validation selects only text frames and checks body properties and transform rotation`() {
        fun shape(body: String, transform: String = "") =
            "<wps:wsp><wps:spPr>$transform</wps:spPr><wps:txbx/>$body</wps:wsp>"
        fun source(shape: String) = WordFrameTextMetadata.validateSource(read(shape), context, ::reject)

        source("<wps:wsp/><wps:other/><other:wsp><wps:txbx/></other:wsp>" + shape(body()))
        source(shape(body(), "<a:xfrm/>"))
        source(shape(body(), """<a:xfrm rot="0"/>"""))
        for ((element, reason) in listOf(
            shape("") to "wsp: Missing or duplicate frame bodyPr",
            shape(body() + body()) to "bodyPr: Missing or duplicate frame bodyPr",
            shape(body(), """<a:xfrm rot="bad"/>""") to "xfrm: Invalid original frame xfrm/@rot",
            shape(body(), """<a:xfrm rot="21600001"/>""") to "xfrm: Invalid original frame xfrm/@rot",
            shape(body(), """<a:xfrm rot="-60000"/>""") to "bodyPr: Shape autofit requires unrotated horizontal text",
        )) assertEquals(reason, assertThrows(IllegalArgumentException::class.java) { source(element) }.message)
    }
}
