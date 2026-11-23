package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.PdfFixture
import dev.nuaforma.pdf.PdfImageRun
import dev.nuaforma.pdf.PdfParagraph
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeSvgPictureModelTest {
    private val drawingPart = "word/document.xml"
    private val relationships = "word/_rels/document.xml.rels"
    private val types = "[Content_Types].xml"
    private val vector = "word/media/logo.svg"
    private val fallback = "word/media/logo.png"
    private val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><path fill="#f00" d="M2 2L8 2 8 8 2 8Z"/></svg>"""
    private val declaration = """<Default Extension="svg" ContentType="image/svg+xml"/>"""

    private fun fixture(): Map<String, ByteArray> {
        val original = requireNotNull(javaClass.getResourceAsStream("/picture-transform/a4-inline.docx")).use { it.readBytes() }
        val parts = PdfFixture.unzip(original).toMutableMap()
        update(parts, types, "</Types>", "$declaration</Types>")
        val link = """<Relationship Id="vector" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/logo.svg"/>"""
        update(parts, relationships, "</Relationships>", "$link</Relationships>")
        val reference = """<a:blip r:embed="logo"><a:extLst><a:ext uri="{96DAC541-7B7A-43D3-8B79-37D633B846F1}">
            <s:svgBlip xmlns:s="http://schemas.microsoft.com/office/drawing/2016/SVG/main" r:embed="vector"/>
            </a:ext></a:extLst></a:blip>"""
        update(parts, drawingPart, """<a:blip r:embed="logo"/>""", reference)
        update(parts, drawingPart, "cx=\"1257300\"", "cx=\"1219200\"")
        parts[vector] = svg.toByteArray()
        return parts
    }

    private fun update(parts: MutableMap<String, ByteArray>, part: String, from: String, to: String) {
        val original = parts.getValue(part).decodeToString()
        assertTrue(from in original, "$part fixture must contain $from")
        parts[part] = original.replace(from, to).toByteArray()
    }

    private fun read(parts: Map<String, ByteArray>) = NativeDocxReader(GenerationContext()) { _, _, _ -> "fixture" }
        .read(PdfFixture.zip(parts))

    private fun rejected(parts: Map<String, ByteArray>, reason: String) = assertThrows(NativeDocxReadException::class.java) {
        read(parts)
    }.also { assertEquals(reason, it.message) }

    @Test
    fun `the qualified internal vector is preferred to the owned bitmap fallback`() {
        val model = read(fixture())
        val picture = model.blocks.filterIsInstance<PdfParagraph>().flatMap { it.runs }.filterIsInstance<PdfImageRun>().single().image
        assertEquals(96f, picture.width)
        assertEquals(96f, picture.height)
        val pixels = picture.bytes().inputStream().use(ImageIO::read)
        assertEquals(0xFFFF0000.toInt(), pixels.getRGB(512, 512))
        assertEquals(0, pixels.getRGB(1, 1))
    }

    @Test
    fun `SVG files require an exact standard content type without per part overrides`() {
        for (invalid in listOf("", declaration + declaration,
            declaration.replace("Default", "Other"), declaration.replace("image/svg+xml", "image/png"),
            declaration.replace("/>", " extra=\"1\"/>"),
            declaration.replace("/>", "><Default/></Default>"),
            declaration.replace("/>", ">text</Default>"))) {
            val source = fixture().toMutableMap()
            update(source, types, declaration, invalid)
            rejected(source, "An embedded SVG requires its standard content type")
        }
        val source = fixture().toMutableMap()
        update(source, types, declaration, declaration + """<Override PartName="/$vector" ContentType="image/svg+xml"/>""")
        rejected(source, "SVG picture content-type overrides are not qualified")
    }

    @Test
    fun `missing fallback bytes and invalid SVG content retain distinct source diagnostics`() {
        val source = fixture()
        rejected(source - fallback, "Missing raster fallback for embedded SVG")
        rejected(source - vector, "Missing embedded SVG picture")
        val missingLink = source.toMutableMap()
        update(missingLink, drawingPart, "r:embed=\"vector\"", "r:embed=\"missing\"")
        rejected(missingLink, "Missing embedded SVG picture")
        val invalid = svg.replace("<path ", "<path stroke=\"black\" ").toByteArray()
        val error = rejected(source + (vector to invalid), "Unsupported SVG path properties")
        assertEquals(vector, error.part)
        assertEquals("svg", error.sourceContext.construct)
    }
}
