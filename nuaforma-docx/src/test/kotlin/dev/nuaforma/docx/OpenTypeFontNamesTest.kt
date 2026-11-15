package dev.nuaforma.docx

import dev.nuaforma.contracts.GenerationException
import dev.nuaforma.contracts.RejectionReason
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OpenTypeFontNamesTest {
    private data class Name(val id: Int, val value: String, val platform: Int = 3, val encoding: Int = 1, val language: Int = 1033)

    private fun table(vararg records: Name): ByteArray {
        val values = records.map { it.value }.distinct().associateWith { it.toByteArray(Charsets.UTF_16BE) }
        val strings = 6 + records.size * 12
        val offsets = mutableMapOf<String, Int>()
        var size = 0
        for ((value, bytes) in values) { offsets[value] = size; size += bytes.size }
        val data = ByteArray(strings + size)
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(2, records.size.toShort()); buffer.putShort(4, strings.toShort())
        for ((index, record) in records.withIndex()) {
            val offset = 6 + index * 12
            buffer.putShort(offset, record.platform.toShort()); buffer.putShort(offset + 2, record.encoding.toShort())
            buffer.putShort(offset + 4, record.language.toShort()); buffer.putShort(offset + 6, record.id.toShort())
            buffer.putShort(offset + 8, values.getValue(record.value).size.toShort()); buffer.putShort(offset + 10, offsets.getValue(record.value).toShort())
        }
        for ((value, bytes) in values) bytes.copyInto(data, strings + offsets.getValue(value))
        return data
    }

    @ParameterizedTest
    @ValueSource(strings = ["Light", "Display", "Condensed", "Black"])
    fun `extended design variants retain their precise family without impersonating the base`(variant: String) {
        val names = table(Name(1, "Example $variant"), Name(2, "Regular"), Name(16, "Example"), Name(17, variant))
        val original = names.clone()
        assertEquals(setOf("Example $variant"), OpenTypeFontNames.families(names))
        assertArrayEquals(original, names)
    }

    @Test
    fun `standard style linked names retain their typographic aliases`() {
        for ((legacy, typographic) in listOf("Regular" to "regular", "Bold" to "Bold", "Italic" to "Italic", "BoldItalic" to "Bold Italic")) {
            val names = table(Name(1, "Legacy Example"), Name(2, legacy), Name(16, "Typographic Example"), Name(17, typographic))
            assertEquals(setOf("Legacy Example", "Typographic Example"), OpenTypeFontNames.families(names))
        }
        val boldDisplay = table(Name(1, "Example Display"), Name(2, "Bold"), Name(16, "Example"), Name(17, "Display Bold"))
        assertEquals(setOf("Example Display"), OpenTypeFontNames.families(boldDisplay))
    }

    @Test
    fun `legacy only and typographic only fonts preserve their existing names`() {
        assertEquals(setOf("Legacy Only"), OpenTypeFontNames.families(table(Name(1, "Legacy Only"))))
        assertEquals(setOf("Typographic Only"), OpenTypeFontNames.families(table(Name(16, "Typographic Only"))))
        assertEquals(setOf("Example", "Alias"), OpenTypeFontNames.families(table(Name(1, "Example"), Name(16, "Alias"))))
    }

    @Test
    fun `platform encoding and language identify a corresponding style pair`() {
        val separateLanguages = table(Name(1, "Legacy"), Name(2, "Regular"), Name(16, "Alias"), Name(17, "Display", language = 1031))
        assertEquals(setOf("Legacy", "Alias"), OpenTypeFontNames.families(separateLanguages))
        val unicode = table(Name(1, "Example Light", platform = 0, encoding = 7, language = 0),
            Name(2, "Regular", platform = 0, encoding = 7, language = 0), Name(16, "Example", platform = 0, encoding = 7, language = 0),
            Name(17, "Light", platform = 0, encoding = 7, language = 0))
        assertEquals(setOf("Example Light"), OpenTypeFontNames.families(unicode))
        val localized = table(Name(1, "Example Display"), Name(2, "Regular"), Name(16, "Example"), Name(17, "Display"),
            Name(1, "Beispiel Anzeige", language = 1031), Name(2, "Standard", language = 1031),
            Name(16, "Beispiel", language = 1031), Name(17, "Anzeige", language = 1031))
        assertEquals(setOf("Example Display", "Beispiel Anzeige"), OpenTypeFontNames.families(localized))
    }

    @Test
    fun `bad or ambiguous style metadata cannot add a broader family`() {
        for (style in listOf("PRIVATE_TOKEN\u0000", "\uFFFD", " ", "x".repeat(129))) {
            assertEquals(setOf("Example Display"), OpenTypeFontNames.families(
                table(Name(1, "Example Display"), Name(2, "Regular"), Name(16, "Example"), Name(17, style))))
        }
        val duplicate = table(Name(1, "Example Display"), Name(2, "Regular"), Name(16, "Example"), Name(17, "Regular"), Name(17, "Display"))
        assertEquals(setOf("Example Display"), OpenTypeFontNames.families(duplicate))
    }

    @Test
    fun `raw naming table budgets and family validation stay strict without leaking names`() {
        val valid = table(Name(1, "Example"))

        fun changed(action: ByteBuffer.() -> Unit): ByteArray = valid.clone().also { action(ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN)) }

        val cases = listOf(
            ByteArray(0) to RejectionReason.UNSUPPORTED_FONT_NAME_TABLE,
            valid.copyOf(5) to RejectionReason.UNSUPPORTED_FONT_NAME_TABLE,
            changed { putShort(0, 2) } to RejectionReason.UNSUPPORTED_FONT_NAME_TABLE,
            changed { putShort(2, 4097) } to RejectionReason.INVALID_FONT_NAME_RECORDS,
            changed { putShort(4, 17) } to RejectionReason.INVALID_FONT_NAME_RECORDS,
            changed { putShort(4, 32767) } to RejectionReason.INVALID_FONT_NAME_RECORDS,
            changed { putShort(16, 32767) } to RejectionReason.FONT_NAME_EXCEEDS_ITS_TABLE,
            changed { putShort(14, 0) } to RejectionReason.INVALID_FONT_FAMILY_NAME,
            changed { putShort(14, 1) } to RejectionReason.INVALID_FONT_FAMILY_NAME,
            table(Name(1, "x".repeat(129))) to RejectionReason.INVALID_FONT_FAMILY_NAME,
            table(Name(1, "PRIVATE_TOKEN\u0000")) to RejectionReason.INVALID_FONT_FAMILY_NAME,
            table(Name(1, " ")) to RejectionReason.INVALID_FONT_FAMILY_NAME,
            table(Name(1, "Example", platform = 1)) to RejectionReason.FONT_NEEDS_A_UNICODE_FAMILY_NAME,
            table(Name(1, "Example", encoding = 2)) to RejectionReason.FONT_NEEDS_A_UNICODE_FAMILY_NAME,
            table(Name(2, "Regular")) to RejectionReason.FONT_NEEDS_A_UNICODE_FAMILY_NAME)
        for ((bytes, reason) in cases) {
            val before = bytes.clone()
            val diagnostic = assertThrows(GenerationException::class.java) { OpenTypeFontNames.families(bytes) }.diagnostics.single()
            assertSame(reason, diagnostic.rejectionReason)
            assertFalse(diagnostic.message.contains("PRIVATE_TOKEN"))
            assertArrayEquals(before, bytes)
        }
        val boundary = table(*Array(4096) { Name(1, "Example") })
        assertEquals(setOf("Example"), OpenTypeFontNames.families(boundary))
    }

    @Test
    fun `families are an immutable snapshot and expose a Java static entry point`() {
        val input = table(Name(1, "Example"))
        val names = OpenTypeFontNames.families(input)
        input.fill(0)
        assertEquals(setOf("Example"), names)
        assertThrows(UnsupportedOperationException::class.java) { (names as MutableSet<String>).add("Other") }
        assertTrue(java.lang.reflect.Modifier.isStatic(OpenTypeFontNames::class.java.getMethod("families", ByteArray::class.java).modifiers))
    }
}
