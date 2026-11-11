package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordPictureMetadataTest {

    private fun picture(locks: String = "", fill: String = "", extension: String = "") = ConversionXml(GenerationContext()).read(
        """<pic:pic xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:a14="http://schemas.microsoft.com/office/drawing/2010/main"><pic:nvPicPr><pic:cNvPicPr>$locks</pic:cNvPicPr></pic:nvPicPr><pic:blipFill><a:blip>$extension</a:blip></pic:blipFill><pic:spPr>$fill</pic:spPr></pic:pic>""".toByteArray())

    private fun normalize(locks: String = "", fill: String = "", extension: String = "") = picture(locks, fill, extension).also {
        WordPictureMetadata.normalize(it) { _, message -> throw NativeDocxReadException(message, "word/document.xml") }
    }

    private fun dpi(value: String) = """<a:extLst><a:ext uri="{28A0092B-C50C-407e-A947-70E740481C1C}"><a14:useLocalDpi val="$value"/></a:ext></a:extLst>"""

    @Test
    fun `editing locks and explicit no fill or no line have no rendering properties`() {
        for (value in listOf("1", "0", "true", "false")) {
            val root = normalize("""<a:picLocks noChangeAspect="$value" noCrop="$value"/>""",
                """<a:noFill/><a:ln w="20116800" cap="rnd" cmpd="dbl" algn="ctr"><a:noFill/></a:ln>""", dpi(value))
            assertFalse(root.descendants().any { it.name.localPart in setOf("picLocks", "noFill", "ln", "extLst") })
        }
        for (style in listOf("", """w="0" cap="flat" cmpd="sng" algn="in"""", """cap="sq" cmpd="thickThin"""",
            """cmpd="thinThick"""", """cmpd="tri""""))
            assertFalse(normalize(fill = "<a:ln $style><a:noFill/></a:ln>").descendants().any { it.name.localPart == "ln" })
    }

    @Test
    fun `unknown locks invalid booleans bounds and nested passive content are rejected`() {
        for (locks in listOf("""<a:picLocks noCrop="yes"/>""", """<a:picLocks noTextEdit="true"/>""",
            "<a:picLocks/><a:picLocks/>", "<a:picLocks>text</a:picLocks>", "<a:picLocks><a:noFill/></a:picLocks>"))
            assertThrows(NativeDocxReadException::class.java) { normalize(locks) }
        for (fill in listOf("<a:noFill/><a:noFill/>", """<a:noFill extra="1"/>""", "<a:noFill>text</a:noFill>",
            """<a:ln w="20116801"><a:noFill/></a:ln>""", """<a:ln w="-1"><a:noFill/></a:ln>""",
            """<a:ln cap="bad"><a:noFill/></a:ln>""", """<a:ln a:w="1"><a:noFill/></a:ln>""", """<a:ln unexpected="1"><a:noFill/></a:ln>"""))
            assertThrows(NativeDocxReadException::class.java) { normalize(fill = fill) }
        assertThrows(NativeDocxReadException::class.java) { normalize(extension = dpi("yes")) }
        assertThrows(NativeDocxReadException::class.java) { normalize(extension = dpi("1").replace("val=\"1\"", "val=\"1\" extra=\"x\"")) }
        assertThrows(NativeDocxReadException::class.java) { normalize(extension = dpi("1").replace("<a14:useLocalDpi", "<a14:unknown")) }
    }

    @Test
    fun `unknown extensions and visible line fills remain for strict source rejection`() {
        val unknown = dpi("0").replace("28A0092B", "00000000")
        val root = normalize(fill = "<a:ln><a:solidFill/></a:ln>", extension = unknown)
        assertTrue(root.descendants().any { it.name.localPart == "extLst" })
        assertTrue(root.descendants().any { it.name.localPart == "solidFill" })
        val duplicate = normalize(extension = dpi("0").replace("</a:extLst>", "<a:ext uri=\"unknown\"/></a:extLst>"))
        assertTrue(duplicate.descendants().any { it.name.localPart == "extLst" })
    }


    private fun storyBlip(name: String, attributes: String) = ConversionXml(GenerationContext()).read(
        """<w:$name xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><a:blip r:embed="image" $attributes/></w:$name>""".toByteArray())

    @Test
    fun `qualified compression states remain unchanged in each supported story root`() {
        for (name in listOf("document", "hdr", "ftr", "footnotes", "endnotes")) for (state in listOf("none", "email", "screen", "print", "hqprint")) {
            val root = storyBlip(name, "cstate=\"$state\"")
            val blip = root.elements().single()
            val original = blip.attributes.toMap()
            WordPictureMetadata.validateSource(root) { _, message -> fail(message) }
            assertEquals(original, blip.attributes)
        }
    }

    @Test
    fun `original invalid compression states unknown attributes and linked pictures are rejected before binding`() {
        for (attributes in listOf("cstate=\"unknown\"", "cstate=\"Print\"", "cstate=\"\"", "a:cstate=\"print\"",
            "extra=\"1\"", "r:link=\"external\"")) {
            val root = storyBlip("document", attributes)
            assertThrows(NativeDocxReadException::class.java) {
                WordPictureMetadata.validateSource(root) { node, message ->
                    assertEquals("blip", node.name.localPart)
                    throw NativeDocxReadException(message, "word/document.xml")
                }
            }
        }
        val theme = ConversionXml(GenerationContext()).read(
            """<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:blip cstate="print"/></a:theme>""".toByteArray())
        WordPictureMetadata.validateSource(theme) { _, message -> fail(message) }
    }

}
