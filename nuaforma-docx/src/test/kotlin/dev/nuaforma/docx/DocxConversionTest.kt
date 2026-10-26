package dev.nuaforma.docx

import dev.nuaforma.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class DocxConversionTest {
    private fun request(text: String) = GenerationRequest(SyntheticDocx.zip(SyntheticDocx.parts()).inputStream(),
        GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.DOCX),
            GenerationOptions(TextWatermark(text, "#127F87", 0.3, -30.0))),
        mapOf("customer" to mapOf("name" to "Ada"), "reference" to "{literal}"))

    @Test fun `conversion shares watermark expression resolution without adding header shapes`() {
        val provider = DocxProvider()
        val source = "{#if true}CONCEPT {reference}{#else}FINAL{/if}"
        val detached = ByteArrayOutputStream()
        val result = provider.generateForConversion(request(source), detached, GenerationContext())
        assertEquals("CONCEPT {literal}", result.watermark!!.text)
        assertEquals("Arial", result.watermarkFontFamily)
        assertEquals(0.3, result.watermark.opacity)
        assertEquals(-30.0, result.watermark.rotation)
        val plain = ByteArrayOutputStream()
        provider.generate(request(source), plain, GenerationContext())
        val original = SyntheticDocx.unzip(plain.toByteArray())
        val conversion = SyntheticDocx.unzip(detached.toByteArray())
        assertEquals(original.keys, conversion.keys)
        assertTrue(original.values.any { it.toString(Charsets.UTF_8).contains("NFWatermark") })
        assertFalse(conversion.values.any { it.toString(Charsets.UTF_8).contains("NFWatermark") })
        for ((name, bytes) in original) {
            if (name.startsWith("word/header")) continue
            if (name.endsWith(".xml") || name.endsWith(".rels")) assertEquals(SyntheticDocx.semantic(bytes), SyntheticDocx.semantic(conversion.getValue(name)), name)
            else assertArrayEquals(bytes, conversion.getValue(name), name)
        }
    }

    @Test fun `empty resolution suppresses decoration and invalid inactive branches still reject`() {
        val output = ByteArrayOutputStream()
        val result = DocxProvider().generateForConversion(request("{#if false}CONCEPT{/if}"), output, GenerationContext())
        assertNull(result.watermark)
        assertNull(result.watermarkFontFamily)
        val rejected = ByteArrayOutputStream()
        assertThrows(GenerationException::class.java) {
            DocxProvider().generateForConversion(request("{#if true}OK{#else}{name | unknownPipe()}{/if}"), rejected, GenerationContext())
        }
        assertEquals(0, rejected.size())
    }
}
