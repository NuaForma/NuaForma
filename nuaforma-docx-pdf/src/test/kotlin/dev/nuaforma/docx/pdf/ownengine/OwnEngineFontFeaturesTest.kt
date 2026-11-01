package dev.nuaforma.docx.pdf.ownengine

import dev.nuaforma.pdf.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class OwnEngineFontFeaturesTest {

    private fun source(name: String) = WordLayoutOracle.resource("/font-features/$name/source.docx")

    private fun runs(name: String) = OwnEngineDocxReader("Noto Serif").read(source(name)).blocks.filterIsInstance<PdfParagraph>()
        .associate { paragraph ->
            val runs = paragraph.runs.filterIsInstance<PdfTextRun>()
            runs.joinToString("") { it.text }.substringBefore(' ') to runs
        }

    @Test fun `effective font size thresholds inheritance direct overrides and compatibility resolve before shaping`() {
        val modern = runs("modern-enabled")

        fun feature(id: String) = modern.getValue(id).first().style.fontFeatures

        assertEquals(PdfKerning.NONE, feature("F01").kerning)
        assertEquals(PdfKerning.OPENTYPE, feature("F02").kerning)
        assertEquals(PdfKerning.NONE, feature("F03").kerning)
        assertEquals(PdfKerning.OPENTYPE, feature("F04").kerning)
        assertTrue(feature("F05").standardLigatures)
        assertFalse(feature("F06").standardLigatures)
        assertEquals(PdfKerning.NONE, feature("F08").kerning)
        assertEquals(PdfKerning.OPENTYPE, feature("F09").kerning)
        assertEquals(PdfFontFeatures(PdfKerning.OPENTYPE, true), feature("F10"))
        assertEquals(PdfFontFeatures(), feature("F11"))
        assertTrue(modern.getValue("F12").all { it.style.fontFeatures == feature("F10") })
        for ((id, values) in runs("modern-disabled"))
            assertEquals(feature(id), values.first().style.fontFeatures, id)
        assertEquals(PdfKerning.PAIRS, runs("legacy-disabled").getValue("F02").first().style.fontFeatures.kerning)
        assertFalse(runs("legacy-disabled").getValue("F05").first().style.fontFeatures.standardLigatures)
        assertEquals(PdfKerning.OPENTYPE, runs("legacy-enabled").getValue("F02").first().style.fontFeatures.kerning)
    }

    @Test fun `independent Word exports qualify OpenType shaping line wraps run boundaries and classic pair kerning`() {
        for (name in listOf("modern-enabled", "modern-disabled", "legacy-enabled", "legacy-disabled", "letter"))
            WordLayoutOracle.qualify("font-features", name, if (name == "letter") 1 else 2,
                Regex("^(F[0-9]{2})\\b"), fontFamily = "Noto Serif")
        WordLayoutOracle.qualify("classic-kerning", "legacy-pairs", 1, Regex("^(F[0-9]{2})\\b"))
    }

    @Test fun `malformed thresholds and unqualified OpenType features reject explicitly`() {
        for ((from, to) in listOf("w:kern w:val=\"40\"" to "w:kern w:val=\"-1\"",
            "w:kern w:val=\"40\"" to "w:kern w:val=\"3277\"",
            "w14:val=\"standard\"" to "w14:val=\"all\"")) {
            val bytes = ByteArrayOutputStream().also { buffer -> ZipOutputStream(buffer).use { output ->
                ZipInputStream(source("modern-enabled").inputStream()).use { input ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        val content = input.readBytes()
                        output.putNextEntry(ZipEntry(entry.name))
                        output.write(if (entry.name == "word/document.xml") content.decodeToString().replace(from, to).toByteArray() else content)
                        output.closeEntry()
                    }
                }
            } }.toByteArray()
            assertThrows(IllegalArgumentException::class.java) { OwnEngineDocxReader("Noto Serif").read(bytes) }
        }
    }

}
