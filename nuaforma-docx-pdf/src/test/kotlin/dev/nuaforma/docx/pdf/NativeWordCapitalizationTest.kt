package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Path

class NativeWordCapitalizationTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `caps follow direct and style declarations after template replacement`() {
        val support = NativeWordFeatureSupport(temporary)
        val parts = PdfFixture.unzip(support.bytes(source)).toMutableMap()
        val styles = listOf(
            style("paragraph", "UseCaps", null, "<w:caps/>"),
            style("paragraph", "OffCaps", "UseCaps", """<w:caps w:val="false"/>"""),
            style("character", "ToggleCaps", null, "<w:caps/>"),
        ).joinToString("")
        for (part in listOf("word/styles.xml", "word/stylesWithEffects.xml")) {
            parts[part]?.let { original ->
                parts[part] = original.decodeToString().replace("</w:styles>", "$styles</w:styles>").toByteArray()
            }
        }
        val body = listOf(
            paragraph("{word} mIx 123", "<w:caps/>"),
            paragraph("Direct Off mIx 123", """<w:caps w:val="off"/>""", "UseCaps"),
            paragraph("Inherited Caps mIx 123", paragraphStyle = "UseCaps"),
            paragraph("Disabled Paragraph mIx 123", paragraphStyle = "OffCaps"),
            paragraph("Character Caps mIx 123", """<w:rStyle w:val="ToggleCaps"/>"""),
            paragraph("Character Toggle mIx 123", """<w:rStyle w:val="ToggleCaps"/>""", "UseCaps"),
            paragraph("Sharp eszett ß und für", """<w:caps/><w:lang w:val="de-DE"/>"""),
            paragraph("Turkish küçük iş", """<w:caps/><w:lang w:val="tr-TR"/>"""),
        ).joinToString("")
        parts[document] = withBody(parts.getValue(document).decodeToString(), body).toByteArray()
        val output = ByteArrayOutputStream()
        support.provider.generate(GenerationRequest(PdfFixture.zip(parts).inputStream(), GenerationRequirements(
            FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.PDF)), mapOf("word" to "Direct Caps")),
            output, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))

        Loader.loadPDF(output.toByteArray()).use { pdf ->
            assertEquals(listOf("DIRECT CAPS MIX 123", "Direct Off mIx 123", "INHERITED CAPS MIX 123",
                "Disabled Paragraph mIx 123", "CHARACTER CAPS MIX 123", "Character Toggle mIx 123",
                "SHARP ESZETT ß UND FÜR", "TURKİSH KÜÇÜK İŞ"),
                PDFTextStripper().getText(pdf).lineSequence().map(String::trim).filter(String::isNotEmpty).toList())
        }
    }

    @Test
    fun `malformed original capitalization controls identify the source property`() {
        val support = NativeWordFeatureSupport(temporary)
        for (invalid in listOf("""<w:caps val="1"/>""", """<w:caps w:val="unknown"/>""", "<w:caps/><w:caps/>",
            "<w:caps>text</w:caps>")) {
            support.rejectChanged(source, document, "caps") { withBody(it, paragraph("Mixed Case", invalid)) }
        }
    }

    private fun style(type: String, id: String, parent: String?, format: String): String {
        val base = parent?.let { """<w:basedOn w:val="$it"/>""" }.orEmpty()
        return """<w:style w:type="$type" w:styleId="$id"><w:name w:val="$id"/>$base<w:rPr>$format</w:rPr></w:style>"""
    }

    private fun paragraph(text: String, format: String = "", paragraphStyle: String? = null): String {
        val style = paragraphStyle?.let { """<w:pStyle w:val="$it"/>""" }.orEmpty()
        return """<w:p><w:pPr>$style<w:spacing w:line="280" w:lineRule="exact"/></w:pPr>""" +
            "<w:r><w:rPr>$format</w:rPr><w:t>$text</w:t></w:r></w:p>"
    }

    private fun withBody(xml: String, body: String) = xml.substringBefore("<w:body>") +
        "<w:body>$body<w:sectPr>" + xml.substringAfter("<w:sectPr>")

    private companion object {
        const val source = "/floating-drawings/control/a4/source.docx"
        const val document = "word/document.xml"
    }
}
