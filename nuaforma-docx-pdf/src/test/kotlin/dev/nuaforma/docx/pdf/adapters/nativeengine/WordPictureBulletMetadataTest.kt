package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class WordPictureBulletMetadataTest {
    private val context = GenerationContext()
    private val xml = ConversionXml(context)
    private val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val r = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private val packageRelationships = "http://schemas.openxmlformats.org/package/2006/relationships"
    private val relsPart = "word/_rels/numbering.xml.rels"
    private val numberingPart = "word/numbering.xml"
    private val imagePart = "word/media/bullet.png"
    private val png = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+X2ioAAAAASUVORK5CYII=",
    )

    private val source = """<w:numbering xmlns:w="$w" xmlns:r="$r" xmlns:v="urn:schemas-microsoft-com:vml"
        xmlns:o="urn:schemas-microsoft-com:office:office">
        <w:numPicBullet w:numPicBulletId="20"><w:pict><v:shape id="_x0000_i1025" type="#_x0000_t75"
        style="width:21.6pt;height:21.6pt;visibility:visible;mso-wrap-style:square" o:bullet="t">
        <v:imagedata r:id="rId1" o:title=""/><o:lock v:ext="edit" aspectratio="t"/>
        </v:shape></w:pict></w:numPicBullet>
        <w:abstractNum w:abstractNumId="20"><w:lvl w:ilvl="0"><w:start w:val="1"/>
        <w:numFmt w:val="bullet"/><w:lvlText w:val="•"/><w:lvlPicBulletId w:val="20"/>
        </w:lvl></w:abstractNum><w:num w:numId="20"><w:abstractNumId w:val="20"/></w:num></w:numbering>"""

    private val relation = """<Relationship Id="rId1" Type="$r/image" Target="media/bullet.png"/>"""

    private fun normalize(definition: String = source, links: String = relation, image: ByteArray = png): XmlElement {
        val numbering = xml.read(definition.toByteArray())
        val relationships = xml.read("""<Relationships xmlns="$packageRelationships">$links</Relationships>""".toByteArray())
        WordPictureBulletMetadata(context).normalize(mapOf(numberingPart to numbering, relsPart to relationships),
            mapOf(imagePart to image)) { part, _, message -> throw IllegalArgumentException("$part $message") }
        return numbering
    }

    @Test
    fun `unused picture bullet metadata is retained only as a bounded numbering reference`() {
        val numbering = normalize()
        assertEquals(listOf("abstractNum", "num"), numbering.elements().map { it.name.localPart })
        val unused = xml.read("""<w:body xmlns:w="$w"><w:p/></w:body>""".toByteArray())
        assertNotNull(NativeDocxNumbering(numbering, unused) { emptyList() })
        val active = xml.read("""<w:body xmlns:w="$w"><w:p><w:pPr><w:numPr><w:numId w:val="20"/>
            </w:numPr></w:pPr></w:p></w:body>""".toByteArray())
        val failure = assertThrows(IllegalArgumentException::class.java) {
            NativeDocxNumbering(numbering, active) { emptyList() }
        }
        assertEquals("Visible picture bullets are outside the qualified profile", failure.message)
    }

    @Test
    fun `external duplicate missing or malformed image relationships fail before use`() {
        for (links in listOf(relation + relation, relation.replace("bullet.png", "missing.png"),
            relation.replace("/image", "/hyperlink"),
            relation.replace("media/bullet.png", "https://example.invalid/picture.png")
                .replace("/>", " TargetMode=\"External\"/>"))) {
            assertThrows(IllegalArgumentException::class.java) { normalize(links = links) }
        }
        assertThrows(IllegalArgumentException::class.java) { normalize(image = ByteArray(8)) }
        assertThrows(IllegalArgumentException::class.java) {
            normalize(definition = source.replace("lvlPicBulletId w:val=\"20\"", "lvlPicBulletId w:val=\"99\""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalize(definition = source.replace("<v:imagedata", "<v:textbox/><v:imagedata"))
        }
    }
}
