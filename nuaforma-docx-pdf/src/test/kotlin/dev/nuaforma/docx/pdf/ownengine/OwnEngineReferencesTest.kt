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

class OwnEngineReferencesTest {
    @TempDir lateinit var temporary: Path
    private val kinds = listOf("a4", "letter", "complex", "wrapped")
    private fun source(kind: String) = requireNotNull(javaClass.getResourceAsStream("/own-engine-references/$kind/source.docx")).use { it.readBytes() }
    private fun fonts() = listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic").map { (id, face) ->
        PdfFont(id, requireNotNull(javaClass.getResourceAsStream("/own-engine-fonts/LiberationSans-$face.ttf")).use { it.readBytes() })
    }
    private fun rewrite(kind: String = "a4", old: String, replacement: String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> ZipInputStream(source(kind).inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val bytes = input.readBytes(); zip.putNextEntry(ZipEntry(entry.name))
                zip.write(if (entry.name == "word/document.xml") {
                    val text = bytes.decodeToString(); check(text.contains(old)); text.replace(old, replacement).toByteArray()
                } else bytes); zip.closeEntry()
            }
        } }
        return output.toByteArray()
    }

    @Test fun `real source fields replace stale caches and retain heading caption and reference semantics`() {
        for (kind in kinds) {
            val model = OwnEngineDocxReader().read(source(kind))
            val paragraphs = model.blocks.filterIsInstance<PdfParagraph>()
            assertEquals(4, paragraphs.count { it.semantics?.role == PdfSemanticRole.HEADING })
            assertEquals(2, paragraphs.count { it.semantics?.role == PdfSemanticRole.CAPTION })
            assertEquals(1, paragraphs.count { it.contents != null })
            assertEquals(if (kind == "complex") "_Caption" else "_Number", model.referenceTargets.single().name)
            assertTrue(model.referenceTargets.single().runs.first() is PdfSequenceField)
            Loader.loadPDF(PdfEngine().render(model, fonts())).use { pdf ->
                val text = PDFTextStripper().getText(pdf)
                assertEquals(3, pdf.numberOfPages, kind)
                assertFalse(text.contains("999")); assertFalse(text.contains("OUTDATED"))
                assertTrue(text.contains("1 Hoofdstuk 1")); assertTrue(text.contains("2 Hoofdstuk 2"))
                assertEquals(2, Regex("Figuur 1 — overzicht").findAll(text).count())
                assertEquals(2, Regex(if (kind == "complex") "figuur 1 — overzicht van de aanvraag op pagina 2" else "figuur 1 op pagina 2").findAll(text).count())
                assertTrue(text.contains("Op deze pagina: 2 van 3; deze sectie: 3 pagina’s."))
                assertTrue(text.contains("Op deze pagina: 3 van 3; deze sectie: 3 pagina’s."))
                assertEquals(1, pdf.documentCatalog.names.dests.names.getValue("_Caption").retrievePageNumber())
                assertTrue(pdf.getPage(0).annotations.isNotEmpty())
                assertNotNull(pdf.documentCatalog.documentOutline.firstChild.firstChild)
                assertEquals(if (kind == "letter") 612f else 595.3f, pdf.getPage(0).mediaBox.width, .01f)
            }
        }
    }

    @Test fun `unsupported executable malformed missing and cyclic source fields reject rather than use cache`() {
        for ((old, replacement) in listOf(
            "SEQ Chapters" to "DDEAUTO malicious",
            "SEQ Chapters" to "INCLUDETEXT https://example.test/document.docx",
            "SEQ Chapters" to "SEQ Chapters \\h",
            "SEQ Chapters" to "SEQ Chapters \\r 10000",
            "SEQ Chapters" to "SEQ Chapters \\* ROMAN",
            "SEQ Chapters" to "SEQ Chapters \\c \\r 2",
            "REF _Number \\h" to "REF _Absent \\h",
            "PAGEREF _Caption \\h" to "PAGEREF _Absent \\h",
            "PAGEREF _Caption \\h" to "PAGEREF _Caption \\p",
            "TOC \\o &quot;1-2&quot; \\h \\z \\u" to "TOC \\t &quot;Normal,1&quot;",
            "<w:bookmarkEnd w:id=\"2\"/>" to "",
            "SEQ Figures \\s 1" to "REF _Number \\h",
        )) {
            val invalid = rewrite(old = old, replacement = replacement)
            val failure = assertThrows(RuntimeException::class.java, {
                PdfEngine().render(OwnEngineDocxReader().read(invalid), fonts())
            }, old)
            assertTrue(failure is IllegalArgumentException || failure is PdfRenderException && failure.code == PdfFailureCode.UNSUPPORTED_TEXT)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OwnEngineDocxReader().read(rewrite("complex", "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r>", ""))
        }
    }

    @Test fun `registered conversion independently validates references and contents in every archival profile`() {
        val validator = PdfProcessValidator(Path.of(System.getProperty("nuaforma.pdf.validation.java")),
            Files.list(Path.of(System.getProperty("nuaforma.pdf.validation"))).use { paths -> paths.filter { it.toString().endsWith(".jar") }.sorted().toList() }, temporary)
        val provider = DocxPdfProvider(NativePdfConfiguration(fonts().map { NativePdfFont("Liberation Sans", it.id, it.bytes()) }, validator))
        for (kind in kinds) for (profile in listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b")) {
            val output = ByteArrayOutputStream()
            provider.generate(GenerationRequest(source(kind).inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile)))), output, GenerationContext())
            Loader.loadPDF(output.toByteArray()).use { pdf ->
                assertEquals(3, pdf.numberOfPages)
                assertEquals(1, pdf.documentCatalog.names.dests.names.getValue("_Caption").retrievePageNumber())
                assertTrue(pdf.getPage(0).annotations.isNotEmpty())
                assertFalse(PDFTextStripper().getText(pdf).contains("999"))
                assertTrue(pdf.pages.all { page -> page.resources.fontNames.all { page.resources.getFont(it).isEmbedded } })
            }
        }
        assertEquals(0L, Files.list(temporary).use { it.count() })
    }
}
