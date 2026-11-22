package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.PdfFixture
import dev.nuaforma.pdf.PdfParagraph
import dev.nuaforma.pdf.PdfTextRun
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeBasicLatinFontHintTest {
    private fun read(hint: String, content: String, direction: String = ""): PdfTextRun {
        val parts = PdfFixture.parts()
        val xml = parts.getValue("word/document.xml").decodeToString()
        val fonts = """<w:rFonts w:ascii="Arial" w:hAnsi="Courier New" w:cs="Times New Roman" w:eastAsia="Calibri" w:hint="$hint"/>"""
        parts["word/document.xml"] = xml.replace("<w:b/>", fonts + direction)
            .replace("Hello {name}", content).toByteArray()
        val reader = NativeDocxReader(GenerationContext()) { family, _, _ ->
            when (family) {
                "Arial" -> "ascii"
                "Courier New" -> "highansi"
                "DejaVu Sans" -> "base"
                else -> error("Inactive font selected: $family")
            }
        }
        return reader.read(PdfFixture.zip(parts)).blocks.filterIsInstance<PdfParagraph>().single()
            .runs.filterIsInstance<PdfTextRun>().single()
    }

    @Test
    fun `ordinary Basic Latin keeps its ASCII font even when other fallback fonts are specified`() {
        for (hint in listOf("cs", "eastAsia")) {
            val run = read(hint, "Case 245, row 2!")
            assertEquals("Case 245, row 2!", run.text)
            assertEquals("ascii", run.style.fontId)
        }
    }

    @Test
    fun `nondefault hints on other characters or forced complex-script text remain qualified separately`() {
        for (hint in listOf("cs", "eastAsia")) {
            assertThrows(NativeDocxReadException::class.java) { read(hint, "Case &#xA7;245") }
            assertThrows(NativeDocxReadException::class.java) { read(hint, "Case 245", """<w:rtl w:val="1"/>""") }
        }
        assertThrows(NativeDocxReadException::class.java) { read("unknown", "Case 245") }
    }
}
