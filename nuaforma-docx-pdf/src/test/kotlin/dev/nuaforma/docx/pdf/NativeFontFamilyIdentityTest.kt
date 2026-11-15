package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.DeploymentFonts
import dev.nuaforma.docx.OpenTypeFontNames
import dev.nuaforma.docx.pdf.internal.PdfFontMetadata
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/** Metadata-only SFNTs expose identity selection; renderable fonts are covered by the real provider suite. */
class NativeFontFamilyIdentityTest {
    @TempDir lateinit var temporary: Path

    private fun nameTable(family: String, typographic: String, style: String): ByteArray {
        val values = listOf(1 to family, 2 to "Regular", 16 to typographic, 17 to style)
        val encoded = values.map { it.second.toByteArray(Charsets.UTF_16BE) }
        val strings = 6 + values.size * 12
        val bytes = ByteArray(strings + encoded.sumOf { it.size })
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(2, values.size.toShort()); buffer.putShort(4, strings.toShort())
        var offset = 0
        for ((index, value) in values.withIndex()) {
            val at = 6 + index * 12
            buffer.putShort(at, 3); buffer.putShort(at + 2, 1); buffer.putShort(at + 4, 1033)
            buffer.putShort(at + 6, value.first.toShort()); buffer.putShort(at + 8, encoded[index].size.toShort())
            buffer.putShort(at + 10, offset.toShort()); encoded[index].copyInto(bytes, strings + offset); offset += encoded[index].size
        }
        return bytes
    }

    private fun font(names: ByteArray): ByteArray {
        val bytes = ByteArray(144 + names.size)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(0, 0x00010000); buffer.putShort(4, 2)
        "OS/2".toByteArray().copyInto(bytes, 12); buffer.putInt(20, 44); buffer.putInt(24, 100)
        "name".toByteArray().copyInto(bytes, 28); buffer.putInt(36, 144); buffer.putInt(40, names.size)
        buffer.putShort(44, 4); buffer.putShort(52, 8)
        names.copyInto(bytes, 144)
        return bytes
    }

    @ParameterizedTest
    @ValueSource(strings = ["Light", "Display", "Condensed", "Black"])
    fun `deployment and PDF declarations agree on exact extended family identity`(variant: String) {
        val family = "Example $variant"
        val names = nameTable(family, "Example", variant)
        val bytes = font(names)
        val before = bytes.clone()
        val expected = setOf(family)
        assertEquals(expected, OpenTypeFontNames.families(names))
        assertEquals(expected, PdfFontMetadata.read(bytes).families)
        assertEquals("regular", PdfFontMetadata.read(bytes).face)
        assertDoesNotThrow { NativePdfFont(family, "regular", bytes) }
        assertThrows(IllegalArgumentException::class.java) { NativePdfFont("Example", "regular", bytes) }
        Files.write(temporary.resolve("variant.ttf"), bytes)
        val deployment = DeploymentFonts.load(emptyList(), temporary)
        assertEquals(expected, deployment.catalog.fonts.map { it.family }.toSet())
        assertEquals(listOf("regular"), deployment.catalog.fonts.map { it.face })
        assertArrayEquals(before, bytes)
    }

    @Test
    fun `mounted standard and display fonts coexist without false identity conflicts`() {
        Files.write(temporary.resolve("base.ttf"), font(nameTable("Example", "Example", "Regular")))
        Files.write(temporary.resolve("display.ttf"), font(nameTable("Example Display", "Example", "Display")))
        val deployment = DeploymentFonts.load(emptyList(), temporary)
        val events = mutableListOf<GenerationLogEvent>()
        deployment.reportFailures(GenerationLogging(LoggingMode.DEVELOPMENT, sink = GenerationLogSink { events += it }))
        assertTrue(events.none { it.code == "MOUNTED_FONT_REJECTED" }, events.map { it.code }.toString())
        assertEquals(setOf("Example" to "regular", "Example Display" to "regular"), deployment.catalog.fonts.map { it.family to it.face }.toSet())
        val sources = deployment.sourcesFor(setOf("Example", "Example Display"), emptySet())
        assertEquals(2, sources.size)
        val fonts = deployment.catalog.fonts.map { declaration ->
            val bytes = sources.getValue(requireNotNull(declaration.resource)).openStream().use { it.readBytes() }
            NativePdfFont(declaration.family, declaration.face, bytes)
        }
        assertEquals(2, NativePdfConfiguration(fonts).fonts.size)
    }
}
