package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.PdfFixture
import dev.nuaforma.pdf.PdfParagraph
import dev.nuaforma.pdf.PdfTextRun
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeUnicodeSpaceModelTest {

    private fun source(name: String) = requireNotNull(javaClass.getResourceAsStream("/unicode-spaces/a4/$name")).use { it.readBytes() }

    private fun text(bytes: ByteArray): String = NativeDocxReader(GenerationContext(), { _, _, _ -> "fixture" }).read(bytes)
        .blocks.filterIsInstance<PdfParagraph>().joinToString("\n") { paragraph -> paragraph.runs.filterIsInstance<PdfTextRun>().joinToString("") { it.text } }

    private fun changed(document: (String) -> String): ByteArray {
        val parts = PdfFixture.unzip(source("source.docx"))
        val before = parts.getValue("word/document.xml").decodeToString()
        val after = document(before)
        require(before != after)
        return PdfFixture.zip(parts.mapValues { (name, bytes) -> if (name == "word/document.xml") after.toByteArray() else bytes })
    }

    @Test
    fun `implicit and explicit nonbreaking spaces remain exact neutral model text`() {
        val source = source("source.docx")
        val before = source.copyOf()
        val actual = text(source)
        assertEquals(text(source("control-source.docx")), actual)
        assertEquals(5, actual.count { it == '\u00a0' })
        assertEquals(4, actual.count { it == '\u2007' })
        assertEquals(4, actual.count { it == '\u202f' })
        assertEquals(actual, text(changed { it.replace("<w:t>", "<w:t xml:space=\"default\">") }))
        assertArrayEquals(before, source)
    }

    @Test
    fun `ordinary XML edge whitespace without preservation follows Word trimming`() {
        for (value in listOf(" leading", "trailing ", "\tleading", "trailing\n")) {
            val input = changed { it.replace("<w:t xml:space=\"preserve\">S1_LEAD_L</w:t>", "<w:t>$value</w:t>") }
            assertEquals(text(source("source.docx")).replace("S1_LEAD_L", value.trim(' ', '\t', '\r', '\n')), text(input))
        }
    }
}
