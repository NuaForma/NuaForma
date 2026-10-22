package dev.nuaforma.docx.pdf.internal

import dev.nuaforma.contracts.RejectionReason
import dev.nuaforma.contracts.DiagnosticCodes
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/** Bounded SFNT metadata only; outline parsing is confined to the renderer sandbox. */
internal class PdfFontMetadata(val families: Set<String>, val face: String, val permissions: Int, val version: Int) {
    fun requirePrintEmbedding() {
        val usage = permissions and 15
        if (usage and 1 != 0 || version >= 3 && usage !in setOf(0, 2, 4, 8)) invalid(RejectionReason.INVALID_OPENTYPE_EMBEDDING_PERMISSIONS)
        if (!(usage == 0 || usage and 12 != 0) || version >= 2 && permissions and 512 != 0) invalid(RejectionReason.FONT_PERMISSIONS_DO_NOT_ALLOW_PDF_OUTLINE_EMBEDDING)
        if (version >= 2 && permissions and 256 != 0) {
            PdfFailure.fail(DiagnosticCodes.UNSUPPORTED_OPTION, RejectionReason.REQUIRED_FULL_FONT_PDF_EMBEDDING_IS_NOT_YET_VERIFIED)
        }
    }

    companion object {
        fun read(bytes: ByteArray): PdfFontMetadata {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            fun range(start: Long, size: Long) { if (start < 0 || size < 0 || start > bytes.size.toLong() - size) invalid(RejectionReason.INVALID_OPENTYPE_TABLE_BOUNDS) }
            fun short(offset: Int): Int { range(offset.toLong(), 2); return buffer.getShort(offset).toInt() and 65535 }
            fun uint(offset: Int): Long { range(offset.toLong(), 4); return buffer.getInt(offset).toLong() and 0xFFFFFFFFL }
            range(0, 12)
            if (uint(0) !in setOf(0x00010000L, 0x4F54544FL)) invalid(RejectionReason.ONLY_STANDALONE_OPENTYPE_OR_TRUETYPE_FONTS_ARE_SUPPORTED)
            val count = short(4)
            if (count !in 1..256) invalid(RejectionReason.INVALID_OPENTYPE_TABLE_COUNT)
            range(12, count.toLong() * 16)
            val tables = mutableMapOf<String, Pair<Int, Int>>()
            val spans = mutableListOf<LongRange>()
            repeat(count) { index ->
                val position = 12 + index * 16
                val tag = String(bytes, position, 4, StandardCharsets.US_ASCII)
                val start = uint(position + 8); val size = uint(position + 12)
                range(start, size)
                if (start < 12 + count * 16 || start % 4 != 0L || size > 0 && spans.any { start <= it.last && start + size - 1 >= it.first }) invalid(RejectionReason.INVALID_OR_OVERLAPPING_OPENTYPE_TABLES)
                if (size > 0) spans.add(start..start + size - 1)
                if (tables.put(tag, start.toInt() to size.toInt()) != null) invalid(RejectionReason.DUPLICATE_OPENTYPE_TABLE)
            }
            val (os2, os2Size) = tables["OS/2"] ?: invalid(RejectionReason.FONT_NEEDS_AN_OS_2_PERMISSIONS_TABLE)
            val version = short(os2)
            val minimum = when (version) { 0 -> 68; 1 -> 86; 2, 3, 4 -> 96; 5 -> 100; else -> invalid(RejectionReason.UNSUPPORTED_OS_2_TABLE_VERSION) }
            if (os2Size < minimum) invalid(RejectionReason.INCOMPLETE_OS_2_TABLE)
            val permissions = short(os2 + 8)
            val selection = short(os2 + 62)
            val face = when { selection and 33 == 33 -> "boldItalic"; selection and 32 != 0 -> "bold"; selection and 1 != 0 -> "italic"; else -> "regular" }
            val (name, size) = tables["name"] ?: invalid(RejectionReason.FONT_NEEDS_A_NAME_TABLE)
            if (size < 6 || short(name) !in 0..1) invalid(RejectionReason.UNSUPPORTED_FONT_NAME_TABLE)
            val names = short(name + 2); val strings = short(name + 4)
            if (names > 4096 || 6L + names * 12L > size || strings < 6 + names * 12 || strings > size) invalid(RejectionReason.INVALID_FONT_NAME_RECORDS)
            val families = mutableSetOf<String>()
            repeat(names) { index ->
                val record = name + 6 + index * 12
                val platform = short(record); val encoding = short(record + 2); val id = short(record + 6)
                val length = short(record + 8); val offset = short(record + 10)
                if (strings.toLong() + offset + length > size) invalid(RejectionReason.FONT_NAME_EXCEEDS_ITS_TABLE)
                if (id in setOf(1, 16) && (platform == 0 || platform == 3 && encoding in setOf(0, 1, 10))) {
                    if (length == 0 || length % 2 != 0 || length > 256) invalid(RejectionReason.INVALID_FONT_FAMILY_NAME)
                    val family = String(bytes, name + strings + offset, length, StandardCharsets.UTF_16BE)
                    if (family.isBlank() || family.any { it.isISOControl() || it == '\uFFFD' }) invalid(RejectionReason.INVALID_FONT_FAMILY_NAME)
                    families.add(family)
                }
            }
            if (families.isEmpty()) invalid(RejectionReason.FONT_NEEDS_A_UNICODE_FAMILY_NAME)
            return PdfFontMetadata(families, face, permissions, version)
        }

        private fun invalid(reason: RejectionReason): Nothing = PdfFailure.fail(DiagnosticCodes.INVALID_REQUEST, reason)
    }
}
