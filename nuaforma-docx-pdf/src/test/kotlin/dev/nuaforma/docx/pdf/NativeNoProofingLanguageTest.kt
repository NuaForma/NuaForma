package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.nativeengine.NativeDocxReader
import dev.nuaforma.pdf.PdfParagraph
import dev.nuaforma.pdf.PdfTextRun
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NativeNoProofingLanguageTest {

    @Test
    fun `Word no-proofing clears inherited Turkish language and casing`() {
        val source = requireNotNull(javaClass.getResourceAsStream("/floating-drawings/control/a4/source.docx")).use { it.readBytes() }
        val base = PdfFixture.unzip(source).toMutableMap()
        val settings = """<w:lang w:val="tr-TR"/>"""
        for (part in listOf("word/styles.xml", "word/stylesWithEffects.xml")) {
            val xml = base[part]?.decodeToString() ?: continue
            val patched = xml.replaceFirst("</w:rPr></w:rPrDefault>", "$settings</w:rPr></w:rPrDefault>")
            base[part] = patched.toByteArray()
        }
        val part = "word/document.xml"
        val document = base.getValue(part).decodeToString()

        fun read(override: String): PdfTextRun {
            val paragraph = """<w:p><w:r><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:caps/>$override</w:rPr>""" +
                "<w:t>istanbul izmir</w:t></w:r></w:p>"
            val doc = document.substringBefore("<w:body>") + "<w:body>$paragraph<w:sectPr>" + document.substringAfter("<w:sectPr>")
            val result = NativeDocxReader(GenerationContext()) { _, _, _ -> "font" }.read(PdfFixture.zip(base + (part to doc.toByteArray())))
            return result.blocks.filterIsInstance<PdfParagraph>().single().runs.single() as PdfTextRun
        }

        val inherited = read("")
        val noProof = read("""<w:lang w:val="x-none" w:eastAsia="x-none"/>""")
        assertEquals("tr-TR", inherited.style.languageTag)
        assertEquals("İSTANBUL İZMİR", inherited.text)
        assertNull(noProof.style.languageTag)
        assertEquals("ISTANBUL IZMIR", noProof.text)
    }
}
