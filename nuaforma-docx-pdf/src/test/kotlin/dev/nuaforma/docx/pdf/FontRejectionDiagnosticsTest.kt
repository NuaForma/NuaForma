package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.internal.PdfFontMetadata
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Synthetic SFNT metadata only: these fixtures deliberately contain no renderable outlines. */
class FontRejectionDiagnosticsTest {
    private fun font(version: Int = 4, selection: Int = 0, platform: Int = 3, encoding: Int = 1,
        nameId: Int = 1, family: String = "Synthetic Family"): ByteArray {
        val name = family.toByteArray(Charsets.UTF_16BE)
        return ByteArray(maxOf(512, 162 + name.size)).apply {
            val buffer = ByteBuffer.wrap(this).order(ByteOrder.BIG_ENDIAN)
            buffer.putInt(0, 0x00010000); buffer.putShort(4, 2)
            "OS/2".toByteArray().copyInto(this, 12)
            buffer.putInt(20, 44); buffer.putInt(24, 100)
            "name".toByteArray().copyInto(this, 28)
            buffer.putInt(36, 144); buffer.putInt(40, 18 + name.size)
            buffer.putShort(44, version.toShort()); buffer.putShort(106, selection.toShort())
            buffer.putShort(146, 1); buffer.putShort(148, 18)
            buffer.putShort(150, platform.toShort()); buffer.putShort(152, encoding.toShort())
            buffer.putShort(156, nameId.toShort()); buffer.putShort(158, name.size.toShort())
            name.copyInto(this, 162)
        }
    }

    private fun changed(action: ByteBuffer.() -> Unit) = font().also { action(ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN)) }

    @Test
    fun `supported metadata versions faces and Unicode names preserve their meaning`() {
        for (version in 0..5) {
            val metadata = PdfFontMetadata.read(font(version))
            assertEquals(setOf("Synthetic Family"), metadata.families)
            metadata.requirePrintEmbedding()
        }
        for ((selection, expected) in listOf(0 to "regular", 1 to "italic", 32 to "bold", 33 to "boldItalic")) {
            assertEquals(expected, PdfFontMetadata.read(font(selection = selection)).face)
        }
        for (encoding in listOf(0, 1, 10)) assertEquals(setOf("Synthetic Family"), PdfFontMetadata.read(font(encoding = encoding)).families)
        assertEquals(setOf("Synthetic Family"), PdfFontMetadata.read(font(platform = 0, encoding = 7, nameId = 16)).families)
        assertEquals(setOf("Synthetic Family"), PdfFontMetadata.read(changed { putShort(144, 1) }).families)
        assertEquals(setOf("Synthetic Family"), PdfFontMetadata.read(changed { putInt(0, 0x4F54544F) }).families)
    }

    @Test
    fun `malformed font metadata retains bounded specific reasons without copying private names`() {
        val cases = listOf(
            ByteArray(0) to RejectionReason.INVALID_OPENTYPE_TABLE_BOUNDS,
            font().copyOf(11) to RejectionReason.INVALID_OPENTYPE_TABLE_BOUNDS,
            font().copyOf(20) to RejectionReason.INVALID_OPENTYPE_TABLE_BOUNDS,
            changed { putInt(0, 0) } to RejectionReason.ONLY_STANDALONE_OPENTYPE_OR_TRUETYPE_FONTS_ARE_SUPPORTED,
            changed { putShort(4, 0) } to RejectionReason.INVALID_OPENTYPE_TABLE_COUNT,
            changed { putShort(4, 257) } to RejectionReason.INVALID_OPENTYPE_TABLE_COUNT,
            changed { putInt(20, -1) } to RejectionReason.INVALID_OPENTYPE_TABLE_BOUNDS,
            changed { putInt(24, -1) } to RejectionReason.INVALID_OPENTYPE_TABLE_BOUNDS,
            changed { putInt(20, 12) } to RejectionReason.INVALID_OR_OVERLAPPING_OPENTYPE_TABLES,
            changed { putInt(20, 45) } to RejectionReason.INVALID_OR_OVERLAPPING_OPENTYPE_TABLES,
            changed { putInt(36, 48) } to RejectionReason.INVALID_OR_OVERLAPPING_OPENTYPE_TABLES,
            changed { putInt(28, 0x4F532F32) } to RejectionReason.DUPLICATE_OPENTYPE_TABLE,
            changed { putInt(12, 0x46414B45) } to RejectionReason.FONT_NEEDS_AN_OS_2_PERMISSIONS_TABLE,
            changed { putInt(28, 0x46414B45) } to RejectionReason.FONT_NEEDS_A_NAME_TABLE,
            font(version = 6) to RejectionReason.UNSUPPORTED_OS_2_TABLE_VERSION,
            changed { putInt(24, 67) } to RejectionReason.INCOMPLETE_OS_2_TABLE,
            changed { putInt(40, 5) } to RejectionReason.UNSUPPORTED_FONT_NAME_TABLE,
            changed { putShort(144, 2) } to RejectionReason.UNSUPPORTED_FONT_NAME_TABLE,
            changed { putShort(146, 4097) } to RejectionReason.INVALID_FONT_NAME_RECORDS,
            changed { putShort(146, 1000) } to RejectionReason.INVALID_FONT_NAME_RECORDS,
            changed { putShort(148, 17) } to RejectionReason.INVALID_FONT_NAME_RECORDS,
            changed { putShort(148, 500) } to RejectionReason.INVALID_FONT_NAME_RECORDS,
            changed { putShort(160, 500) } to RejectionReason.FONT_NAME_EXCEEDS_ITS_TABLE,
            changed { putShort(158, 0) } to RejectionReason.INVALID_FONT_FAMILY_NAME,
            changed { putShort(158, 1) } to RejectionReason.INVALID_FONT_FAMILY_NAME,
            font(family = "x".repeat(129)) to RejectionReason.INVALID_FONT_FAMILY_NAME,
            font(family = " ") to RejectionReason.INVALID_FONT_FAMILY_NAME,
            font(family = "PRIVATE_TOKEN\u0000") to RejectionReason.INVALID_FONT_FAMILY_NAME,
            font(family = "PRIVATE_TOKEN\uFFFD") to RejectionReason.INVALID_FONT_FAMILY_NAME,
            font(platform = 1) to RejectionReason.FONT_NEEDS_A_UNICODE_FAMILY_NAME,
            font(encoding = 2) to RejectionReason.FONT_NEEDS_A_UNICODE_FAMILY_NAME,
            font(nameId = 2) to RejectionReason.FONT_NEEDS_A_UNICODE_FAMILY_NAME,
        )
        for ((bytes, expected) in cases) {
            val original = bytes.clone()
            val diagnostic = assertThrows(GenerationException::class.java) { PdfFontMetadata.read(bytes) }.diagnostics.single()
            assertEquals(DiagnosticCodes.INVALID_REQUEST, diagnostic.code)
            assertSame(expected, diagnostic.rejectionReason)
            assertEquals(expected.message, diagnostic.message)
            assertFalse(diagnostic.message.contains("PRIVATE_TOKEN"))
            assertTrue(expected.remediation.contains("font"))
            assertArrayEquals(original, bytes)
        }
    }
}
