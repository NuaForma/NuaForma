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

class NativeInactiveSmallCapsTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `disabled small caps preserve text and metrics on runs and paragraph marks`() {
        val source = requireNotNull(javaClass.getResourceAsStream(fixture)).use { it.readBytes() }
        val archive = PdfFixture.unzip(source)
        val original = archive.getValue(part).decodeToString()
        fun read(control: String): PdfTextRun {
            val bytes = PdfFixture.zip(archive + (part to document(original, control).toByteArray()))
            val model = NativeDocxReader(GenerationContext()) { _, _, _ -> "qualified-font" }.read(bytes)
            return model.blocks.filterIsInstance<PdfParagraph>().single().runs.single() as PdfTextRun
        }

        val baseline = read("")
        assertEquals("MiXeD text", baseline.text)
        for (toggle in listOf("0", "false", "off")) {
            assertEquals(baseline, read("""<w:smallCaps w:val="$toggle"/>"""))
        }

        val off = document(original, """<w:smallCaps w:val="off"/>""")
        val input = PdfFixture.zip(archive + (part to off.toByteArray()))
        Loader.loadPDF(NativeWordFeatureSupport(temporary).generate(input, "standard")).use {
            val output = PDFTextStripper().getText(it).lineSequence().single { line -> line.endsWith(" text") }
            assertEquals("MiXeD text", output)
        }
    }

    @Test
    fun `active and malformed small caps identify the source property`() {
        val support = NativeWordFeatureSupport(temporary)
        for (invalid in listOf("<w:smallCaps/>", """<w:smallCaps w:val="wrong"/>""")) {
            support.rejectChanged(fixture, part, "smallCaps") { document(it, invalid) }
        }
    }

    private fun document(xml: String, control: String): String {
        val paragraph = "<w:p><w:pPr><w:rPr>$control</w:rPr></w:pPr><w:r><w:rPr>$control</w:rPr><w:t>MiXeD text</w:t></w:r></w:p>"
        return xml.substringBefore("<w:body>") + "<w:body>$paragraph<w:sectPr>" + xml.substringAfter("<w:sectPr>")
    }

    private companion object {
        const val fixture = "/floating-drawings/control/a4/source.docx"
        const val part = "word/document.xml"
    }
}
