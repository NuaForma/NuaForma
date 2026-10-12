package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.internal.PdfFontMetadata
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PdfFontMetadataTest {
    private fun font(version: Int = 4, permissions: Int = 0): ByteArray {
        val bytes = javaClass.getResourceAsStream("/fonts/LibreBarcode39-Regular.ttf")!!.use { it.readBytes() }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val count = buffer.getShort(4).toInt() and 65535
        val entry = (0 until count).map { 12 + it * 16 }.single { String(bytes, it, 4, Charsets.US_ASCII) == "OS/2" }
        val offset = buffer.getInt(entry + 8)
        buffer.putShort(offset, version.toShort()); buffer.putShort(offset + 8, permissions.toShort())
        return bytes
    }

    @Test
    fun `static PDF allows print preview permissions without editable DOCX rights`() {
        for (permissions in listOf(0, 4, 8)) PdfFontMetadata.read(font(permissions = permissions)).requirePrintEmbedding()
        assertTrue("Libre Barcode 39" in PdfFontMetadata.read(font()).families)
        assertEquals("regular", PdfFontMetadata.read(font()).face)
    }

    @Test
    fun `restricted bitmap and invalid permissions are enforced without changing flags`() {
        for (permissions in listOf(2, 512, 1, 12)) {
            val bytes = font(permissions = permissions)
            val original = bytes.clone()
            assertEquals(DiagnosticCodes.INVALID_REQUEST, assertThrows(GenerationException::class.java) {
                PdfFontMetadata.read(bytes).requirePrintEmbedding()
            }.diagnostics.single().code)
            assertArrayEquals(original, bytes)
        }
        assertEquals(DiagnosticCodes.UNSUPPORTED_OPTION, assertThrows(GenerationException::class.java) {
            PdfFontMetadata.read(font(permissions = 256)).requirePrintEmbedding()
        }.diagnostics.single().code)
    }

    @Test
    fun `historical OS2 versions use specified permission rules`() {
        PdfFontMetadata.read(font(version = 2, permissions = 12)).requirePrintEmbedding()
        PdfFontMetadata.read(font(version = 1, permissions = 256 or 512)).requirePrintEmbedding()
        assertEquals(DiagnosticCodes.INVALID_REQUEST, assertThrows(GenerationException::class.java) {
            PdfFontMetadata.read(font(version = 3, permissions = 12)).requirePrintEmbedding()
        }.diagnostics.single().code)
    }
}
