package dev.nuaforma.docx

import dev.nuaforma.contracts.DiagnosticCodes
import dev.nuaforma.contracts.RejectionReason
import dev.nuaforma.docx.internal.DocxFailure
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.Locale

/** Bounded family names from an OpenType name table; glyphs and embedding rights are not inspected. */
object OpenTypeFontNames {
    private data class NamingKey(val platform: Int, val encoding: Int, val language: Int)

    /** Preserve exact four-face families and omit aliases that describe a different typographic style group. */
    @JvmStatic
    fun families(nameTable: ByteArray): Set<String> {
        if (nameTable.size < 6) invalid(RejectionReason.UNSUPPORTED_FONT_NAME_TABLE)
        val buffer = ByteBuffer.wrap(nameTable).order(ByteOrder.BIG_ENDIAN)

        fun unsignedShort(offset: Int): Int = buffer.getShort(offset).toInt() and 65535

        if (unsignedShort(0) !in 0..1) invalid(RejectionReason.UNSUPPORTED_FONT_NAME_TABLE)
        val count = unsignedShort(2)
        val strings = unsignedShort(4)
        if (count > 4096 || 6L + count * 12L > nameTable.size || strings < 6 + count * 12 || strings > nameTable.size)
            invalid(RejectionReason.INVALID_FONT_NAME_RECORDS)
        val families = mutableSetOf<String>()
        val typographicFamilies = mutableSetOf<String>()
        val legacyStyles = mutableMapOf<NamingKey, MutableSet<String>>()
        val typographicStyles = mutableMapOf<NamingKey, MutableSet<String>>()
        var unqualifiedStyle = false
        repeat(count) { index ->
            val record = 6 + index * 12
            val platform = unsignedShort(record)
            val encoding = unsignedShort(record + 2)
            val id = unsignedShort(record + 6)
            val length = unsignedShort(record + 8)
            val offset = unsignedShort(record + 10)
            if (strings.toLong() + offset + length > nameTable.size) invalid(RejectionReason.FONT_NAME_EXCEEDS_ITS_TABLE)
            if (id !in setOf(1, 2, 16, 17) || !(platform == 0 || platform == 3 && encoding in setOf(0, 1, 10))) return@repeat
            val value = unicodeName(nameTable, strings + offset, length)
            if (id == 1 || id == 16) {
                if (value == null) invalid(RejectionReason.INVALID_FONT_FAMILY_NAME)
                if (id == 1) families += value else typographicFamilies += value
            } else if (value == null) unqualifiedStyle = true
            else {
                val key = NamingKey(platform, encoding, unsignedShort(record + 4))
                val styles = if (id == 2) legacyStyles else typographicStyles
                styles.getOrPut(key) { mutableSetOf() } += value.lowercase(Locale.ROOT).filterNot(Char::isWhitespace)
            }
        }
        val extendedGroup = unqualifiedStyle || legacyStyles.any { (key, styles) ->
            typographicStyles[key]?.let { it != styles } == true
        }
        if (families.isEmpty() || !extendedGroup) families += typographicFamilies
        if (families.isEmpty()) invalid(RejectionReason.FONT_NEEDS_A_UNICODE_FAMILY_NAME)
        return Collections.unmodifiableSet(LinkedHashSet(families))
    }

    private fun unicodeName(bytes: ByteArray, offset: Int, length: Int): String? {
        if (length == 0 || length % 2 != 0 || length > 256) return null
        val value = String(bytes, offset, length, StandardCharsets.UTF_16BE)
        return value.takeUnless { it.isBlank() || it.any { character -> character.isISOControl() || character == '\uFFFD' } }
    }

    private fun invalid(reason: RejectionReason): Nothing = DocxFailure.fail(DiagnosticCodes.INVALID_REQUEST, reason)

}
