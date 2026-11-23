package dev.nuaforma.docx.pdf

import org.apache.pdfbox.Loader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class NativeNonbreakingHyphenTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `Word paints an ordinary hyphen and keeps both fragments together at the margin`() {
        val support = NativeWordFeatureSupport(temporary)
        val parts = PdfFixture.unzip(support.bytes(source)).toMutableMap()
        val xml = parts.getValue(part).decodeToString()
        parts[part] = changed(xml, "<w:noBreakHyphen/>").toByteArray()
        val markers = Regex("PREFIX|ALPHABET-OMEGACOMPANY")

        Loader.loadPDF(support.generate(PdfFixture.zip(parts), "standard")).use { pdf ->
            val positions = support.positions(pdf, markers)
            assertEquals(setOf("PREFIX", "ALPHABET-OMEGACOMPANY"), positions.keys)
            val prefix = positions.getValue("PREFIX")
            val compound = positions.getValue("ALPHABET-OMEGACOMPANY")
            assertEquals(prefix.x, compound.x, .01f)
            assertTrue(compound.y >= prefix.y + 13f)
        }
    }

    @Test
    fun `malformed original nonbreaking hyphens report the source element`() {
        val support = NativeWordFeatureSupport(temporary)
        for (invalid in listOf("<w:noBreakHyphen w:val=\"1\"/>", "<w:noBreakHyphen>text</w:noBreakHyphen>")) {
            support.rejectChanged(source, part, "noBreakHyphen") { changed(it, invalid) }
        }
    }

    private fun changed(xml: String, hyphen: String) = xml.substringBefore("<w:body>") +
        """<w:body><w:p><w:pPr><w:ind w:right="6638"/><w:spacing w:line="280" w:lineRule="exact"/></w:pPr>""" +
        """<w:r><w:t xml:space="preserve">PREFIX </w:t></w:r><w:r><w:t>ALPHABET</w:t>$hyphen<w:t>OMEGACOMPANY</w:t></w:r></w:p>""" +
        "<w:sectPr>" + xml.substringAfter("<w:sectPr>")

    private companion object {
        const val source = "/floating-drawings/control/a4/source.docx"
        const val part = "word/document.xml"
    }
}
