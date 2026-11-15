package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.GenerationException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class NativeExactLeadingTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `exact-leading ink matches Word without treating the virtual origin as a physical page`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        for (variant in listOf("10pt-20line", "12pt-12line", "18pt-12line", "18pt-10line", "18pt-20line")) {
            val profiles = if (variant == "18pt-10line" && paper == "a4") listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b")
                else listOf("standard")
            support.verify("/exact-leading-ink/$variant/$paper/", profiles, Regex("M[0-9]{2}_(?:BEFORE|AFTER)|ÅÉHgjpq"), 3)
        }
    }

    @Test
    fun `real DOCX rejects physical ink overflow with measured causes and no PDF`() {
        val support = NativeWordFeatureSupport(temporary)
        val parts = linkedMapOf<String, ByteArray>()
        ZipInputStream(support.bytes("/exact-leading-ink/18pt-10line/a4/source.docx").inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                parts[entry.name] = input.readBytes()
            }
        }
        val original = parts.getValue("word/document.xml").decodeToString()
        val paragraphs = Regex("<w:p>.*?</w:p>", RegexOption.DOT_MATCHES_ALL).findAll(original).map { it.value }.toList()
        assertEquals(3, paragraphs.size)
        val section = Regex("<w:sectPr>.*?</w:sectPr>", RegexOption.DOT_MATCHES_ALL).find(original)!!.value
            .replace("w:top=\"1440\"", "w:top=\"0\"").replace("w:bottom=\"1440\"", "w:bottom=\"0\"")
        for (edge in listOf("top", "bottom")) {
            val before = if (edge == "bottom") paragraphs.first().replace("w:line=\"400\"", "w:line=\"16638\"") else ""
            parts["word/document.xml"] = (original.substringBefore("<w:body>") + "<w:body>" + before + paragraphs[1] +
                section + "</w:body>" + original.substringAfter("</w:body>")).toByteArray()
            for (profile in listOf("standard", "pdfa-3b")) {
                val output = ByteArrayOutputStream()
                val error = assertThrows(GenerationException::class.java) { support.generate(PdfFixture.zip(parts), profile, output) }
                val detail = error.diagnostics.single().sourceContext!!.explanation
                val reason = if (edge == "top") "Text ascent exceeds physical page top" else "Text descent exceeds physical page bottom"
                val measured = Regex("CONTENT_OVERFLOW: $reason; required=([0-9.]+) pt; available=([0-9.]+) pt").matchEntire(detail)
                assertNotNull(measured, detail)
                assertTrue(measured!!.groupValues[1].toFloat() > measured.groupValues[2].toFloat())
                assertEquals(0, output.size())
            }
        }
    }
}
