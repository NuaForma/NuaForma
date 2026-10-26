package dev.nuaforma.docx.pdf.ownengine

import dev.nuaforma.pdf.*
import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.DocxPdfProvider
import dev.nuaforma.docx.pdf.NativePdfConfiguration
import dev.nuaforma.docx.pdf.NativePdfFont
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class OwnEngineNotesTest {
    @TempDir lateinit var temporary: Path
    private val kinds = listOf("a4", "letter", "multipage", "split-note", "split-endnote", "automatic", "table")
    private fun source(kind: String) = requireNotNull(javaClass.getResourceAsStream("/own-engine-notes/$kind/source.docx")).use { it.readBytes() }
    private fun fonts() = listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic").map { (id, face) ->
        PdfFont(id, requireNotNull(javaClass.getResourceAsStream("/own-engine-fonts/LiberationSans-$face.ttf")).use { it.readBytes() })
    }
    private fun rewrite(part: String, old: String, replacement: String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> ZipInputStream(source("a4").inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val bytes = input.readBytes(); zip.putNextEntry(ZipEntry(entry.name))
                zip.write(if (entry.name == part) {
                    val text = bytes.decodeToString(); check(text.contains(old)); text.replace(old, replacement).toByteArray()
                } else bytes); zip.closeEntry()
            }
        } }
        return output.toByteArray()
    }

    @Test fun `source notes preserve numbering typography all text and bidirectional navigation across pages`() {
        for (kind in kinds) {
            val model = OwnEngineDocxReader().read(source(kind))
            assertEquals(if (kind == "split-note") 2 else 3, model.notes.size)
            assertEquals(listOf("1", "2"), model.notes.filter { it.kind == PdfNoteKind.FOOTNOTE }.map { it.number.text })
            val marker = model.blocks.filterIsInstance<PdfParagraph>().flatMap { it.runs }.filterIsInstance<PdfNoteCall>().first()
            assertEquals(7f, marker.style.size); assertEquals(4f, marker.style.baselineShift)
            val label = model.notes.first().paragraphs.first().runs.first() as PdfNoteLabel
            assertEquals(6f, label.style.size); assertEquals(3f, label.style.baselineShift)
            Loader.loadPDF(PdfEngine().render(model, fonts())).use { pdf ->
                assertEquals(when (kind) { "split-endnote" -> 3; "multipage", "split-note" -> 2; else -> 1 }, pdf.numberOfPages)
                val text = PDFTextStripper().getText(pdf)
                assertTrue(text.contains("END De laatste alinea")); assertTrue(text.contains("Een korte toelichting"))
                assertTrue(text.contains("Сведения")); assertTrue(text.contains("στοιχεία"))
                if (kind == "split-note" || kind == "split-endnote") for (index in 1..if (kind == "split-note") 38 else 65)
                    assertEquals(1, Regex("N${if (kind == "split-note") 2 else 1}\\.${index.toString().padStart(2, '0')}(?![0-9])").findAll(text).count())
                assertTrue(pdf.documentCatalog.names.dests.names.keys.containsAll(listOf("source-footnote1", "source-footnote1-call")))
                assertTrue(pdf.pages.sumOf { it.annotations.size } >= model.notes.size * 2)
                assertEquals(if (kind == "letter") 612f else 595.3f, pdf.getPage(0).mediaBox.width, .01f)
            }
        }
    }

    @Test fun `missing duplicate nested custom or unsupported notes reject explicitly`() {
        val cases = listOf(
            Triple("word/document.xml", "<w:footnoteReference w:id=\"1\"/>", "<w:footnoteReference w:id=\"99\"/>"),
            Triple("word/document.xml", "<w:footnoteReference w:id=\"2\"/>", "<w:footnoteReference w:id=\"1\"/>"),
            Triple("word/document.xml", "<w:footnoteReference w:id=\"1\"/>", "<w:footnoteReference w:id=\"1\" w:customMarkFollows=\"1\"/>"),
            Triple("word/document.xml", "w:val=\"continuous\"", "w:val=\"eachPage\""),
            Triple("word/document.xml", "w:val=\"pageBottom\"", "w:val=\"beneathText\""),
            Triple("word/footnotes.xml", "<w:footnoteRef/>", "<w:endnoteRef/>"),
            Triple("word/footnotes.xml", "<w:footnoteRef/>", "<w:footnoteReference w:id=\"1\"/>"),
            Triple("word/footnotes.xml", "<w:separator/>", "<w:t>Custom separator</w:t>"),
            Triple("word/styles.xml", "w:val=\"superscript\"", "w:val=\"subscript\""),
        )
        for ((part, old, replacement) in cases) assertThrows(RuntimeException::class.java, {
            PdfEngine().render(OwnEngineDocxReader().read(rewrite(part, old, replacement)), fonts())
        }, "$part: $replacement")
    }

    @Test fun `registered conversion embeds note fonts and independently validates every archival profile`() {
        val validator = PdfProcessValidator(Path.of(System.getProperty("nuaforma.pdf.validation.java")),
            Files.list(Path.of(System.getProperty("nuaforma.pdf.validation"))).use { paths -> paths.filter { it.toString().endsWith(".jar") }.sorted().toList() }, temporary)
        val provider = DocxPdfProvider(NativePdfConfiguration(fonts().map { NativePdfFont("Liberation Sans", it.id, it.bytes()) }, validator))
        for (kind in kinds) for (profile in listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b")) {
            val output = ByteArrayOutputStream()
            provider.generate(GenerationRequest(source(kind).inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile)))), output, GenerationContext())
            Loader.loadPDF(output.toByteArray()).use { pdf ->
                assertTrue(PDFTextStripper().getText(pdf).contains("Een korte toelichting"))
                assertTrue(pdf.documentCatalog.names.dests.names.containsKey("source-footnote1-call"))
                assertTrue(pdf.pages.all { page -> page.resources.fontNames.all { page.resources.getFont(it).isEmbedded } })
            }
        }
        assertEquals(0L, Files.list(temporary).use { it.count() })
    }
}
