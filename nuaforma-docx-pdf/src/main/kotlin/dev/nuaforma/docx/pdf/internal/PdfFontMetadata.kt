package dev.nuaforma.docx.pdf.internal

import dev.nuaforma.contracts.DiagnosticCodes
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/** Bounded SFNT metadata only; outline parsing is confined to the renderer sandbox. */
internal class PdfFontMetadata(val families: Set<String>, val face: String, val permissions: Int, val version: Int) {
    fun requirePrintEmbedding() {
        val usage = permissions and 15
        if (usage and 1 != 0 || version >= 3 && usage !in setOf(0, 2, 4, 8)) invalid("Invalid OpenType embedding permissions")
        if (!(usage == 0 || usage and 12 != 0) || version >= 2 && permissions and 512 != 0) invalid("Font permissions do not allow PDF outline embedding")
        if (version >= 2 && permissions and 256 != 0) {
            PdfFailure.fail(DiagnosticCodes.UNSUPPORTED_OPTION, "Required full-font PDF embedding is not yet verified; no-subsetting fonts cannot use this backend profile")
        }
    }

    companion object {
        fun read(bytes: ByteArray): PdfFontMetadata {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            fun range(start: Long, size: Long) { if (start < 0 || size < 0 || start > bytes.size.toLong() - size) invalid("Invalid OpenType table bounds") }
            fun short(offset: Int): Int { range(offset.toLong(), 2); return buffer.getShort(offset).toInt() and 65535 }
            fun uint(offset: Int): Long { range(offset.toLong(), 4); return buffer.getInt(offset).toLong() and 0xFFFFFFFFL }
            range(0, 12)
            if (uint(0) !in setOf(0x00010000L, 0x4F54544FL)) invalid("Only standalone OpenType or TrueType fonts are supported")
            val count = short(4)
            if (count !in 1..256) invalid("Invalid OpenType table count")
            range(12, count.toLong() * 16)
            val tables = mutableMapOf<String, Pair<Int, Int>>()
            val spans = mutableListOf<LongRange>()
            repeat(count) { index ->
                val position = 12 + index * 16
                val tag = String(bytes, position, 4, StandardCharsets.US_ASCII)
                val start = uint(position + 8); val size = uint(position + 12)
                range(start, size)
                if (start < 12 + count * 16 || start % 4 != 0L || size > 0 && spans.any { start <= it.last && start + size - 1 >= it.first }) invalid("Invalid or overlapping OpenType tables")
                if (size > 0) spans.add(start..start + size - 1)
                if (tables.put(tag, start.toInt() to size.toInt()) != null) invalid("Duplicate OpenType table")
            }
            val (os2, os2Size) = tables["OS/2"] ?: invalid("Font needs an OS/2 permissions table")
            val version = short(os2)
            val minimum = when (version) { 0 -> 68; 1 -> 86; 2, 3, 4 -> 96; 5 -> 100; else -> invalid("Unsupported OS/2 table version") }
            if (os2Size < minimum) invalid("Incomplete OS/2 table")
            val permissions = short(os2 + 8)
            val selection = short(os2 + 62)
            val face = when { selection and 33 == 33 -> "boldItalic"; selection and 32 != 0 -> "bold"; selection and 1 != 0 -> "italic"; else -> "regular" }
            val (name, size) = tables["name"] ?: invalid("Font needs a name table")
            if (size < 6 || short(name) !in 0..1) invalid("Unsupported font name table")
            val names = short(name + 2); val strings = short(name + 4)
            if (names > 4096 || 6L + names * 12L > size || strings < 6 + names * 12 || strings > size) invalid("Invalid font name records")
            val families = mutableSetOf<String>()
            repeat(names) { index ->
                val record = name + 6 + index * 12
                val platform = short(record); val encoding = short(record + 2); val id = short(record + 6)
                val length = short(record + 8); val offset = short(record + 10)
                if (strings.toLong() + offset + length > size) invalid("Font name exceeds its table")
                if (id in setOf(1, 16) && (platform == 0 || platform == 3 && encoding in setOf(0, 1, 10))) {
                    if (length == 0 || length % 2 != 0 || length > 256) invalid("Invalid font family name")
                    val family = String(bytes, name + strings + offset, length, StandardCharsets.UTF_16BE)
                    if (family.isBlank() || family.any { it.isISOControl() || it == '\uFFFD' }) invalid("Invalid font family name")
                    families.add(family)
                }
            }
            if (families.isEmpty()) invalid("Font needs a Unicode family name")
            return PdfFontMetadata(families, face, permissions, version)
        }

        private fun invalid(message: String): Nothing = PdfFailure.fail(DiagnosticCodes.INVALID_REQUEST, message)
    }
}
