package dev.nuaforma.docx.internal

import dev.nuaforma.contracts.RejectionReason
import dev.nuaforma.contracts.DiagnosticCodes
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/** Bounded metadata inspection, not a rasterizer or a full OpenType validator. */
internal class OpenTypeMetadata private constructor(val families: Set<String>, val face: String, val embeddingBits: Int, private val os2Version: Int,
    val panose: ByteArray, val unicodeRanges: List<String>, val codePageRanges: List<String>) {
    fun requireEditableEmbedding() {
        val usage = embeddingBits and 15
        if (usage and 1 != 0 || os2Version >= 3 && usage !in setOf(0, 2, 4, 8)) fail(RejectionReason.INVALID_OPENTYPE_EMBEDDING_PERMISSIONS)
        // OS/2 versions 0-2 permit the historical least-restrictive interpretation.
        val editable = usage == 0 || usage and 8 != 0
        val bitmapOnly = os2Version >= 2 && embeddingBits and 512 != 0
        if (!editable || bitmapOnly) fail(RejectionReason.FONT_PERMISSIONS_DO_NOT_ALLOW_EDITABLE_OUTLINE_EMBEDDING)
    }

    companion object {
        fun read(bytes: ByteArray): OpenTypeMetadata {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            fun range(start: Long, size: Long) {
                if (start < 0 || size < 0 || start > bytes.size.toLong() - size) fail(RejectionReason.INVALID_OPENTYPE_TABLE_BOUNDS)
            }
            fun short(offset: Int): Int { range(offset.toLong(), 2); return buffer.getShort(offset).toInt() and 65535 }
            fun uint(offset: Int): Long { range(offset.toLong(), 4); return buffer.getInt(offset).toLong() and 0xFFFFFFFFL }
            range(0, 12)
            if (uint(0) != 0x00010000L && uint(0) != 0x4F54544FL) fail(RejectionReason.ONLY_STANDALONE_OPENTYPE_OR_TRUETYPE_FONTS_ARE_SUPPORTED)
            val count = short(4)
            if (count !in 1..256) fail(RejectionReason.INVALID_OPENTYPE_TABLE_COUNT)
            range(12, count.toLong() * 16)
            val tables = mutableMapOf<String, Pair<Int, Int>>()
            val spans = mutableListOf<LongRange>()
            repeat(count) { index ->
                val position = 12 + index * 16
                val tag = String(bytes, position, 4, StandardCharsets.US_ASCII)
                val start = uint(position + 8); val size = uint(position + 12)
                range(start, size)
                if (start < 12 + count * 16 || start % 4 != 0L || size > 0 && spans.any { start <= it.last && start + size - 1 >= it.first }) fail(RejectionReason.INVALID_OR_OVERLAPPING_OPENTYPE_TABLES)
                if (size > 0) spans.add(start..start + size - 1)
                if (tables.put(tag, start.toInt() to size.toInt()) != null) fail(RejectionReason.DUPLICATE_OPENTYPE_TABLE)
            }
            val (os2, os2Size) = tables["OS/2"] ?: fail(RejectionReason.FONT_NEEDS_AN_OS_2_PERMISSIONS_TABLE)
            val version = short(os2)
            val minimumSize = when (version) { 0 -> 68; 1 -> 86; 2, 3, 4 -> 96; 5 -> 100; else -> fail(RejectionReason.UNSUPPORTED_OS_2_TABLE_VERSION) }
            if (os2Size < minimumSize) fail(RejectionReason.INCOMPLETE_OS_2_TABLE)
            val permissions = short(os2 + 8)
            val selection = short(os2 + 62)
            val face = when {
                selection and 33 == 33 -> "boldItalic"
                selection and 32 != 0 -> "bold"
                selection and 1 != 0 -> "italic"
                else -> "regular"
            }
            val (name, nameSize) = tables["name"] ?: fail(RejectionReason.FONT_NEEDS_A_NAME_TABLE)
            if (nameSize < 6 || short(name) !in 0..1) fail(RejectionReason.UNSUPPORTED_FONT_NAME_TABLE)
            val names = short(name + 2); val strings = short(name + 4)
            if (names > 4096 || 6L + names * 12L > nameSize || strings < 6 + names * 12 || strings > nameSize) fail(RejectionReason.INVALID_FONT_NAME_RECORDS)
            val families = mutableSetOf<String>()
            repeat(names) { index ->
                val record = name + 6 + index * 12
                val platform = short(record); val encoding = short(record + 2); val id = short(record + 6)
                val size = short(record + 8); val offset = short(record + 10)
                if (strings.toLong() + offset + size > nameSize) fail(RejectionReason.FONT_NAME_EXCEEDS_ITS_TABLE)
                if (id in setOf(1, 16) && (platform == 0 || platform == 3 && encoding in setOf(0, 1, 10))) {
                    if (size == 0 || size % 2 != 0 || size > 256) fail(RejectionReason.INVALID_FONT_FAMILY_NAME)
                    val family = String(bytes, name + strings + offset, size, StandardCharsets.UTF_16BE)
                    if (family.isBlank() || family.any { it.isISOControl() || it == '\uFFFD' }) fail(RejectionReason.INVALID_FONT_FAMILY_NAME)
                    families.add(family)
                }
            }
            if (families.isEmpty()) fail(RejectionReason.FONT_NEEDS_A_UNICODE_FAMILY_NAME)
            fun hex(offset: Int) = uint(offset).toString(16).uppercase(java.util.Locale.ROOT).padStart(8, '0')
            val unicode = (0..3).map { hex(os2 + 42 + it * 4) }
            val codePages = if (short(os2) >= 1 && os2Size >= 86) listOf(hex(os2 + 78), hex(os2 + 82)) else listOf("00000000", "00000000")
            return OpenTypeMetadata(families, face, permissions, short(os2), bytes.copyOfRange(os2 + 32, os2 + 42), unicode, codePages)
        }

        private fun fail(reason: RejectionReason): Nothing = DocxFailure.fail(DiagnosticCodes.INVALID_REQUEST, reason)
    }
}
