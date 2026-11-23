package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.nativeengine.NativeDocxReader
import dev.nuaforma.pdf.PdfParagraph
import dev.nuaforma.pdf.PdfTextRun
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class NativeActiveSmallCapsTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `direct and inherited small caps reduce only originally lowercase letters`() {
        val active = "<w:smallCaps/>"
        val off = """<w:smallCaps w:val="false"/>"""
        val character = """<w:rStyle w:val="SmallCharacter"/>"""
        val derived = """<w:rStyle w:val="InheritedSmallCharacter"/>"""
        val disabled = """<w:rStyle w:val="DisabledSmallCharacter"/>"""
        val source = archive(
            paragraph("AaB 19", active) +
                paragraph("AaB 19", paragraphStyle = "SmallParagraph") +
                paragraph("AaB 19", character) +
                paragraph("AaB 19", derived) +
                paragraph("AaB 19", disabled) +
                paragraph("AaB 19", character, "SmallParagraph") +
                paragraph("AaB 19", off, "SmallParagraph") +
                paragraph("AaB 19", active + "<w:caps/>") +
                paragraph("AaB 19", paragraphExtra = "<w:rPr>$active</w:rPr>"),
        )
        val model = NativeDocxReader(GenerationContext()) { _, _, _ -> "qualified-font" }.read(source)
        val paragraphs = model.blocks.filterIsInstance<PdfParagraph>()
        assertEquals(9, paragraphs.size)
        val reduced = listOf(Triple("A", 12f, null), Triple("A", 9.5f, 12f), Triple("B", 12f, null),
            Triple(" ", 9.5f, 12f), Triple("19", 12f, null))
        for (index in 0..3) {
            val runs = paragraphs[index].runs.filterIsInstance<PdfTextRun>()
            assertEquals(reduced, runs.map { Triple(it.text, it.style.size, it.style.lineMetricSize) })
        }
        assertEquals(listOf("AaB 19", "AaB 19", "AaB 19", "AAB 19", "AaB 19"),
            paragraphs.drop(4).map { p -> p.runs.filterIsInstance<PdfTextRun>().joinToString("") { it.text } })
    }

    @Test
    fun `small caps preserve natural baselines and words split across glyph sizes`() {
        val support = NativeWordFeatureSupport(temporary)
        val active = "<w:smallCaps/>"
        fun document(markup: String) = archive(paragraph("lead", markup) + paragraph("follow", markup) +
            paragraph("Z MiXeDcaps WORD", markup, paragraphExtra = """<w:ind w:right="7920"/>"""))
        val base = support.generate(document(""), "standard")
        val shaded = support.generate(document(active), "standard")
        Loader.loadPDF(base).use { plain ->
            Loader.loadPDF(shaded).use { reduced ->
                val baseline = support.positions(plain, Regex("lead|follow"))
                val actual = support.positions(reduced, Regex("LEAD|FOLLOW"))
                assertEquals(baseline.getValue("lead").y, actual.getValue("LEAD").y, .01f)
                assertEquals(baseline.getValue("follow").y, actual.getValue("FOLLOW").y, .01f)
                val text = PDFTextStripper().getText(reduced).lineSequence().map(String::trim).toList()
                assertEquals(1, text.count { it.contains("MIXEDCAPS") })
            }
        }
    }

    @Test
    fun `nonstandard spaces and combining glyph sequences retain source diagnostics`() {
        val support = NativeWordFeatureSupport(temporary)
        for (text in listOf("a\u00a0b", "a\u0301b")) {
            support.rejectChanged("/floating-drawings/control/a4/source.docx", "word/document.xml", "/w:r[1]") { raw ->
                raw.substringBefore("<w:body>") + "<w:body>${paragraph(text, "<w:smallCaps/>")}<w:sectPr>" + raw.substringAfter("<w:sectPr>")
            }
        }
    }

    private fun archive(body: String): ByteArray {
        val source = requireNotNull(javaClass.getResourceAsStream("/floating-drawings/control/a4/source.docx")).use { it.readBytes() }
        val parts = PdfFixture.unzip(source).toMutableMap()
        val styles = paragraphStyle("SmallParagraph", "paragraph", "Normal", "<w:smallCaps/>") +
            paragraphStyle("SmallCharacter", "character", null, "<w:smallCaps/>") +
            paragraphStyle("InheritedSmallCharacter", "character", "SmallCharacter", "") +
            paragraphStyle("DisabledSmallCharacter", "character", "SmallCharacter", """<w:smallCaps w:val="false"/>""")
        for (name in listOf("word/styles.xml", "word/stylesWithEffects.xml")) {
            parts[name]?.let { parts[name] = it.decodeToString().replace("</w:styles>", "$styles</w:styles>").toByteArray() }
        }
        val name = "word/document.xml"
        val original = parts.getValue(name).decodeToString()
        parts[name] = (original.substringBefore("<w:body>") + "<w:body>$body<w:sectPr>" + original.substringAfter("<w:sectPr>")).toByteArray()
        return PdfFixture.zip(parts)
    }

    private fun paragraph(text: String, props: String = "", paragraphStyle: String? = null, paragraphExtra: String = ""): String {
        val style = paragraphStyle?.let { """<w:pStyle w:val="$it"/>""" }.orEmpty()
        return """<w:p><w:pPr>$style<w:spacing w:before="0" w:after="0"/>$paragraphExtra</w:pPr>""" +
            """<w:r><w:rPr><w:sz w:val="24"/>$props</w:rPr><w:t>$text</w:t></w:r></w:p>"""
    }

    private fun paragraphStyle(id: String, type: String, parent: String?, props: String): String {
        val base = parent?.let { """<w:basedOn w:val="$it"/>""" }.orEmpty()
        return """<w:style w:type="$type" w:styleId="$id"><w:name w:val="$id"/>$base<w:rPr>$props</w:rPr></w:style>"""
    }
}
