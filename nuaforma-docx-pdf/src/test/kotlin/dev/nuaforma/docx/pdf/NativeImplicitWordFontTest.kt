package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.nativeengine.NativeDocxReader
import dev.nuaforma.pdf.PdfParagraph
import dev.nuaforma.pdf.PdfTextRun
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NativeImplicitWordFontTest {

    @Test
    fun `a theme-free Word package uses Times New Roman for text without a font family`() {
        val source = requireNotNull(javaClass.getResourceAsStream("/floating-drawings/control/a4/source.docx")).use { it.readBytes() }
        val parts = PdfFixture.unzip(source).toMutableMap()
        val noFonts = Regex("""<w:rFonts\b[^>]*/>""")
        for (part in listOf("word/styles.xml", "word/stylesWithEffects.xml")) {
            parts[part]?.let { bytes -> parts[part] = bytes.decodeToString().replace(noFonts, "").toByteArray() }
        }
        val part = "word/document.xml"
        val document = parts.getValue(part).decodeToString()
        val paragraph = "<w:p><w:r><w:rPr><w:sz w:val=\"22\"/></w:rPr><w:t>FONT_MARKER</w:t></w:r></w:p>"
        parts[part] = (document.substringBefore("<w:body>") + "<w:body>$paragraph<w:sectPr>" +
            document.substringAfter("<w:sectPr>")).toByteArray()

        val fontRequests = mutableSetOf<String>()
        val result = NativeDocxReader(GenerationContext()) { font, _, _ ->
            fontRequests += font
            "font"
        }.read(PdfFixture.zip(parts))
        val contents = result.blocks.filterIsInstance<PdfParagraph>().single().runs.single() as PdfTextRun
        assertEquals("FONT_MARKER", contents.text)
        assertEquals(setOf("Times New Roman"), fontRequests)
    }
}
