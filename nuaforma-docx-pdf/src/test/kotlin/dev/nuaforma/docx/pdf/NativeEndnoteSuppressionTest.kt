package dev.nuaforma.docx.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class NativeEndnoteSuppressionTest {
    @TempDir lateinit var temporary: Path
    private val notesSource = "/note-separator-formatting/endnotes/control/a4/source.docx"

    @Test
    fun aDocumentWithOnlySeparatorsKeepsItsPrintedContent() {
        val support = NativeWordFeatureSupport(temporary)
        val parts = PdfFixture.unzip(support.bytes(notesSource)).toMutableMap()
        val documentPart = "word/document.xml"
        val endnotePart = "word/endnotes.xml"
        val references = Regex("""<w:endnoteReference\b[^>]*/>""")
        val definitions = Regex("""<w:endnote w:id="\d+">.*?</w:endnote>""", RegexOption.DOT_MATCHES_ALL)
        val originalDocument = parts.getValue(documentPart).decodeToString()
        val originalEndnotes = parts.getValue(endnotePart).decodeToString()
        assertEquals(12, references.findAll(originalDocument).count())
        assertEquals(12, definitions.findAll(originalEndnotes).count())
        val document = originalDocument.replace(references, "")
        parts[endnotePart] = originalEndnotes.replace(definitions, "").toByteArray()
        parts[documentPart] = document.toByteArray()
        val baseline = PdfFixture.zip(parts)
        parts[documentPart] = withFlag(document, "<w:noEndnote/>").toByteArray()
        val suppressed = PdfFixture.zip(parts)
        for (profile in listOf("standard", "pdfa-3b")) {
            fun text(source: ByteArray) = Loader.loadPDF(support.generate(source, profile)).use { pdf ->
                pdf.numberOfPages to PDFTextStripper().getText(pdf)
            }
            val control = text(baseline)
            assertTrue(control.second.isNotBlank())
            assertEquals(control, text(suppressed))
        }
    }

    @Test
    fun activeSuppressionAndMalformedFlagsAreReportedAtTheSectionProperty() {
        val support = NativeWordFeatureSupport(temporary)
        for (flag in listOf("<w:noEndnote/>", """<w:noEndnote w:val="invalid"/>""")) {
            support.rejectChanged(notesSource, "word/document.xml", "noEndnote") { withFlag(it, flag) }
        }
        val parts = PdfFixture.unzip(support.bytes(notesSource)).toMutableMap()
        val document = parts.getValue("word/document.xml").decodeToString()
        val ordinary = PdfFixture.zip(parts)
        parts["word/document.xml"] = withFlag(document, """<w:noEndnote w:val="false"/>""").toByteArray()
        fun text(source: ByteArray) = Loader.loadPDF(support.generate(source, "standard")).use { PDFTextStripper().getText(it) }
        assertEquals(text(ordinary), text(PdfFixture.zip(parts)))
    }

    private fun withFlag(document: String, flag: String): String =
        document.replaceFirst("</w:sectPr>", "$flag</w:sectPr>")
}
