package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import java.util.Base64
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordPictureBulletShapeValidationTest {
    private val context = GenerationContext()
    private val xml = ConversionXml(context)
    private val numbering = "word/numbering.xml"
    private val relationships = "word/_rels/numbering.xml.rels"
    private val image = "word/media/bullet.png"
    private val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a2ioAAAAASUVORK5CYII=")
    private val formulas = List(12) { index -> """<v:f eqn="val $index"/>""" }.joinToString("")
    private val lock = """<o:lock v:ext="edit" aspectratio="t"/>"""
    private val shapeType = """<v:shapetype id="_x0000_t75" o:spt="75" coordsize="21600,21600">
        <v:stroke joinstyle="miter"/><v:formulas>$formulas</v:formulas><v:path o:connecttype="rect"/>$lock
        </v:shapetype>"""
    private val shape = """<v:shape id="Bullet1" type="#_x0000_t75" o:bullet="t"
        style="width:12.5pt;height:10pt;visibility:visible;mso-wrap-style:square"><v:imagedata r:id="rId1" o:title=""/></v:shape>"""
    private val reference = """<w:abstractNum><w:lvl><w:lvlPicBulletId w:val="1"/></w:lvl></w:abstractNum>"""
    private val link = """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/bullet.png"/>"""

    private fun bullet(contents: String = shapeType + shape) =
        """<w:numPicBullet w:numPicBulletId="1"><w:pict>$contents</w:pict></w:numPicBullet>"""

    private fun read(definitions: String? = bullet() + reference, links: String? = link, assets: Map<String, ByteArray> = mapOf(image to png)) =
        buildMap {
            if (definitions != null) put(numbering, xml.read("""
                <w:numbering xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:v="urn:schemas-microsoft-com:vml"
                  xmlns:o="urn:schemas-microsoft-com:office:office" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                  xmlns:x="urn:other">$definitions</w:numbering>
                """.trimIndent().toByteArray()))
            if (links != null) put(relationships, xml.read("""
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">$links</Relationships>
                """.trimIndent().toByteArray()))
        }.also { roots ->
            WordPictureBulletMetadata(context).normalize(roots, assets) { part, node, reason ->
                throw IllegalArgumentException("$part/${node.name.localPart}: $reason")
            }
        }

    @Test
    fun `unused saved bitmap definitions allow the canonical VML type and preserve level references`() {
        for (pict in listOf(shape, shapeType + shape, shapeType + shape.replace("</v:shape>", "$lock</v:shape>"))) {
            val root = read(bullet(pict) + reference).getValue(numbering)
            assertEquals(listOf("abstractNum"), root.elements().map { it.name.localPart })
            assertEquals(1, root.descendants().count { it.name.localPart == "lvlPicBulletId" })
        }
        assertTrue(read(null, null).isEmpty())
    }

    @Test
    fun `unowned or malformed definitions and visible level relationships have precise errors`() {
        val invalidDefinition = "Invalid picture-bullet definition"
        val cases = listOf(
            reference to "$relationships/Relationships: Picture-bullet relationships require definitions",
            bullet().replace("w:numPicBullet ", "x:numPicBullet ").replace("/w:numPicBullet>", "/x:numPicBullet>") to "$numbering/numPicBullet: $invalidDefinition",
            bullet().replace("w:numPicBulletId=", "x:id=") to "$numbering/numPicBullet: $invalidDefinition",
            bullet().replace("w:numPicBulletId=\"1\"", "w:numPicBulletId=\"1000001\"") to "$numbering/numPicBullet: $invalidDefinition",
            bullet().replace("<w:pict>", "text<w:pict>") to "$numbering/numPicBullet: $invalidDefinition",
            bullet() + bullet() to "$numbering/numPicBullet: $invalidDefinition",
            bullet().replace("<w:pict>", "<w:pict w:hint=\"1\">") to "$numbering/numPicBullet: Unsupported saved picture-bullet content",
            bullet().replace("<w:pict>", "<w:pict>text") to "$numbering/numPicBullet: Unsupported saved picture-bullet content",
            bullet("") to "$numbering/pict: Picture bullets require a saved VML bitmap",
            bullet(shape + shapeType) to "$numbering/pict: Picture bullets require a saved VML bitmap",
            bullet(shapeType + shapeType + shape) to "$numbering/pict: Unsupported picture-bullet shape type",
            bullet(shape.replace("rId1", "rId2")) to "$numbering/shape: Missing picture-bullet bitmap relationship",
        )
        for ((definition, reason) in cases) assertEquals(reason,
            assertThrows(IllegalArgumentException::class.java) { read(definition) }.message)
        for (level in listOf(reference.replace("w:val=\"1\"", "w:val=\"2\""), reference.replace("w:val=", "x:val="),
            reference.replace("w:val=\"1\"/>", "w:val=\"1\">text</w:lvlPicBulletId>"),
            reference.replace("w:lvlPicBulletId", "x:lvlPicBulletId"))) {
            assertEquals("$numbering/lvlPicBulletId: Invalid picture-bullet level reference",
                assertThrows(IllegalArgumentException::class.java) { read(bullet() + level) }.message)
        }
        assertEquals("$numbering/lvlPicBulletId: Picture-bullet level reference requires a definition",
            assertThrows(IllegalArgumentException::class.java) { read(reference, null) }.message)
        assertEquals("$numbering/numPicBullet: Picture-bullet definitions require owned relationships",
            assertThrows(IllegalArgumentException::class.java) { read(bullet(), null) }.message)
    }

    @Test
    fun `a saved VML bullet accepts only the qualified type and bitmap properties`() {
        val badTypes = listOf(
            shapeType.replace("v:shapetype", "x:shapetype"), shapeType.replace("o:spt=\"75\"", "o:spt=\"74\""),
            shapeType.replace("id=\"_x0000_t75\"", "id=\"other\""), shapeType.replace("coordsize=", "unknown="),
            shapeType.replace("<v:stroke", "text<v:stroke"), shapeType.replace("<v:stroke joinstyle=\"miter\"/>", ""),
            shapeType.replace("v:stroke", "v:path"), shapeType.replace("joinstyle=", "unknown="),
            shapeType.replace("joinstyle=\"miter\"/>", "joinstyle=\"miter\">text</v:stroke>"),
            shapeType.replace("v:formulas", "v:path"), shapeType.replace("<v:formulas>", "<v:formulas bad=\"1\">"),
            shapeType.replace("<v:formulas>", "<v:formulas>text"), shapeType.replaceFirst("<v:f eqn=\"val 0\"/>", ""),
            shapeType.replaceFirst("<v:f ", "<v:path "), shapeType.replaceFirst("eqn=", "unknown="),
            shapeType.replaceFirst("eqn=\"val 0\"/>", "eqn=\"val 0\">text</v:f>"),
            shapeType.replace("v:path", "v:other"), shapeType.replace("o:connecttype=", "o:unknown="),
            shapeType.replace("o:connecttype=\"rect\"/>", "o:connecttype=\"rect\">text</v:path>"),
            shapeType.replace("o:lock", "v:lock"),
        )
        for (type in badTypes) assertEquals("$numbering/pict: Unsupported picture-bullet shape type",
            assertThrows(IllegalArgumentException::class.java) { read(bullet(type + shape)) }.message)
        val badShapes = listOf(shape.replace("type=", "x:type="), shape.replace("Bullet1", "1Invalid"), shape.replace("#_x0000_t75", "#bad"),
            shape.replace("o:bullet=\"t\"", "o:bullet=\"f\""), shape.replace("12.5pt", "12.5mm"),
            shape.replace("<v:imagedata", "text<v:imagedata"), shape.replace("v:imagedata", "v:other"),
            shape.replace("o:title=\"\"", "o:title=\"visible\""), shape.replace("o:title=", "x:title="),
            shape.replace("<v:imagedata r:id=\"rId1\" o:title=\"\"/>", ""),
            shape.replace("</v:shape>", "<o:lock unexpected=\"1\"/></v:shape>"),
            shape.replace("</v:shape>", "$lock$lock</v:shape>"))
        for (element in badShapes) assertEquals("$numbering/shape: Unsupported picture-bullet bitmap shape",
            assertThrows(IllegalArgumentException::class.java) { read(bullet(element)) }.message)
    }

    @Test
    fun `numbering images require internal owned PNG bytes and unique relationship identities`() {
        val invalid = "$relationships/Relationship: Picture-bullet relationships require existing internal PNG bitmaps"
        for (bad in listOf(link.replace("rId1", "1invalid"), link.replace("/image", "/hyperlink"),
            link.replace("media/bullet.png", "../bullet.png"), link.replace("Target=", "Unknown="),
            link.replace("Id=", "Unknown="), link.replace("/>", ">text</Relationship>"), link + link)) {
            assertEquals(invalid, assertThrows(IllegalArgumentException::class.java) { read(links = bad) }.message)
        }
        for (bytes in listOf(byteArrayOf(0), byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7))) {
            assertEquals(invalid, assertThrows(IllegalArgumentException::class.java) { read(assets = mapOf(image to bytes)) }.message)
        }
        assertEquals(invalid, assertThrows(IllegalArgumentException::class.java) { read(assets = emptyMap()) }.message)
        assertEquals("$relationships/Relationships: Unexpected unowned numbering image relationships",
            assertThrows(IllegalArgumentException::class.java) { read(links = link + link.replace("rId1", "rId2")) }.message)
    }
}
