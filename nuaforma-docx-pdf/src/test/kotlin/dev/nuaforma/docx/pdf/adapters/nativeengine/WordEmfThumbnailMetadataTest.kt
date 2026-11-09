package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordEmfThumbnailMetadataTest {
    private val context = GenerationContext()
    private val xml = ConversionXml(context)
    private val relationships = "http://schemas.openxmlformats.org/package/2006/relationships"

    private fun parsed(target: String = "docProps/thumbnail.emf", mode: String = "", type: String = "image/x-emf") = linkedMapOf(
        "_rels/.rels" to xml.read("""<Relationships xmlns="$relationships"><Relationship Id="preview" Type="$relationships/metadata/thumbnail" Target="$target" $mode/></Relationships>""".toByteArray()),
        "[Content_Types].xml" to xml.read("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="emf" ContentType="$type"/></Types>""".toByteArray()))

    @Test
    fun `EMF previews are opaque bytes and cannot activate templates fonts or metafile commands`() {
        val payload = "{not a visible expression or a decoded metafile".toByteArray()
        val parts = mapOf("docProps/thumbnail.emf" to payload)
        val roots = parsed()
        WordThumbnailMetadata(context).normalize(roots, parts)
        assertTrue(roots.getValue("_rels/.rels").elements().isEmpty())
        assertArrayEquals(payload, parts.getValue("docProps/thumbnail.emf"))
    }

    @Test
    fun `EMF preview relationships types uniqueness and nonempty payloads remain guarded`() {
        val parts = mapOf("docProps/thumbnail.emf" to byteArrayOf(1))
        for (roots in listOf(parsed(mode = "TargetMode=\"External\""), parsed(target = "https://example.invalid/thumb.emf"),
            parsed(target = "../docProps/thumbnail.emf"), parsed(type = "image/jpeg"), parsed(type = "application/octet-stream"))) {
            assertThrows(NativeDocxReadException::class.java) { WordThumbnailMetadata(context).normalize(roots, parts) }
        }
        val duplicate = parsed()
        duplicate.getValue("_rels/.rels").children += duplicate.getValue("_rels/.rels").elements().single()
        assertThrows(NativeDocxReadException::class.java) { WordThumbnailMetadata(context).normalize(duplicate, parts) }
        assertThrows(NativeDocxReadException::class.java) { WordThumbnailMetadata(context).normalize(parsed(), mapOf("docProps/thumbnail.emf" to byteArrayOf())) }
        assertThrows(NativeDocxReadException::class.java) { WordThumbnailMetadata(context).normalize(parsed(), parts + ("docProps/thumbnail.png" to byteArrayOf(1))) }
        val orphan = parsed().apply { remove("_rels/.rels") }
        assertEquals("docProps/thumbnail.emf", assertThrows(NativeDocxReadException::class.java) {
            WordThumbnailMetadata(context).normalize(orphan, parts)
        }.part)
    }

}
