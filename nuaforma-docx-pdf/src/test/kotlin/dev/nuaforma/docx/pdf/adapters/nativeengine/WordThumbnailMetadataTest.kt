package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordThumbnailMetadataTest {
    private val context = GenerationContext()
    private val relations = "http://schemas.openxmlformats.org/package/2006/relationships"

    private fun parsed(target: String = "docProps/thumbnail.jpeg", mode: String = "", type: String = "image/jpeg") = linkedMapOf(
        "_rels/.rels" to ConversionXml(context).read("""<Relationships xmlns="$relations"><Relationship Id="preview" Type="$relations/metadata/thumbnail" Target="$target" $mode/></Relationships>""".toByteArray()),
        "[Content_Types].xml" to ConversionXml(context).read("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="jpeg" ContentType="$type"/></Types>""".toByteArray()))

    @Test
    fun `preview bytes are opaque and never become print content`() {
        val parts = mapOf("docProps/thumbnail.jpeg" to "{not a template or a printed image".toByteArray())
        val parsed = parsed()
        WordThumbnailMetadata(context).normalize(parsed, parts)
        assertTrue(parsed.getValue("_rels/.rels").elements().isEmpty())
        assertArrayEquals("{not a template or a printed image".toByteArray(), parts.getValue("docProps/thumbnail.jpeg"))
    }

    @Test
    fun `external mismatched duplicate orphan empty and nonimage previews remain rejected`() {
        val parts = mapOf("docProps/thumbnail.jpeg" to byteArrayOf(1))
        for (parsed in listOf(parsed(mode = "TargetMode=\"External\""), parsed(target = "https://example.invalid/thumb.jpeg"),
            parsed(type = "application/octet-stream"), parsed(target = "../docProps/thumbnail.jpeg"))) {
            assertThrows(NativeDocxReadException::class.java) { WordThumbnailMetadata(context).normalize(parsed, parts) }
        }
        assertThrows(NativeDocxReadException::class.java) { WordThumbnailMetadata(context).normalize(parsed(), emptyMap()) }
        assertThrows(NativeDocxReadException::class.java) { WordThumbnailMetadata(context).normalize(parsed(), mapOf("docProps/thumbnail.jpeg" to byteArrayOf())) }
        val duplicate = parsed()
        val root = duplicate.getValue("_rels/.rels")
        root.children += root.elements().single()
        assertThrows(NativeDocxReadException::class.java) { WordThumbnailMetadata(context).normalize(duplicate, parts) }
        val orphan = parsed()
        orphan.getValue("_rels/.rels").children.clear()
        val failure = assertThrows(NativeDocxReadException::class.java) { WordThumbnailMetadata(context).normalize(orphan, parts) }
        assertEquals("docProps/thumbnail.jpeg", failure.part)
    }

}
