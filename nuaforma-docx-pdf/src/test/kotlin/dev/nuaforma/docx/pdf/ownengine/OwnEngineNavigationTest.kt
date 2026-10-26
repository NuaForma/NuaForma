package dev.nuaforma.docx.pdf.ownengine

import dev.nuaforma.pdf.*
import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.DocxPdfProvider
import dev.nuaforma.docx.pdf.NativePdfConfiguration
import dev.nuaforma.docx.pdf.NativePdfFont
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
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

class OwnEngineNavigationTest {
    @TempDir lateinit var temporary: Path
    private val kinds = listOf("a4", "letter", "table", "multipage")
    private fun source(kind: String) = requireNotNull(javaClass.getResourceAsStream("/own-engine-navigation/$kind/source.docx")).use { it.readBytes() }
    private fun fonts() = listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic").map { (id, face) ->
        PdfFont(id, requireNotNull(javaClass.getResourceAsStream("/own-engine-fonts/LiberationSans-$face.ttf")).use { it.readBytes() })
    }
    private fun rewrite(part: String, old: String, replacement: String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            ZipInputStream(source("a4").inputStream()).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val bytes = input.readBytes()
                    zip.putNextEntry(ZipEntry(entry.name))
                    zip.write(if (entry.name == part) {
                        val text = bytes.decodeToString(); check(text.contains(old)); text.replace(old, replacement).toByteArray()
                    } else bytes)
                    zip.closeEntry()
                }
            }
        }
        return output.toByteArray()
    }

    @Test fun `source navigation retains wrapping colors text and physical target pages`() {
        for (kind in kinds) {
            val model = OwnEngineDocxReader().read(source(kind))
            val linked = model.blocks.filterIsInstance<PdfParagraph>().flatMap { it.runs }.filterIsInstance<PdfTextRun>().filter { it.navigation?.link != null }
            assertFalse(linked.isEmpty())
            assertTrue(linked.all { it.style.color == PdfColor(0, 80, 160) && it.style.underline }, "Source link styling must survive translation")
            Loader.loadPDF(PdfEngine().render(model, fonts())).use { pdf ->
            val text = PDFTextStripper().getText(pdf)
            for (marker in listOf("N001", "N002", "N003", "N099")) assertEquals(1, Regex(marker).findAll(text).count(), kind)
            assertEquals(pdf.numberOfPages - 1, pdf.documentCatalog.names.dests.names.getValue("Conclusion").retrievePageNumber())
            assertEquals("Conclusion", pdf.documentCatalog.documentOutline.firstChild.title)
            assertEquals(if (kind == "letter") 612f else 595.3f, pdf.getPage(0).mediaBox.width, .01f)
            val links = pdf.pages.flatMap { it.annotations }.map { it as PDAnnotationLink }
            assertTrue(links.any { (it.action as? PDActionURI)?.uri == "https://nuaforma.dev/image-comparison/" })
            assertTrue(links.any { (it.action as? PDActionURI)?.uri == "mailto:example@example.test" })
            assertTrue(links.count { (it.destination as? PDNamedDestination)?.namedDestination == "Conclusion" } >= 2)
            }
        }
    }

    @Test fun `external resource loading malformed links bookmarks and unqualified picture links reject`() {
        val relations = "word/_rels/document.xml.rels"
        val document = "word/document.xml"
        for ((part, old, replacement) in listOf(
            Triple(relations, "https://nuaforma.dev/image-comparison/", "file:///etc/passwd"),
            Triple(relations, "https://nuaforma.dev/image-comparison/", "javascript:alert(1)"),
            Triple(relations, "https://nuaforma.dev/image-comparison/", "https://user:pass@example.test/"),
            Triple(relations, "relationships/hyperlink", "relationships/image"),
            Triple(document, "w:anchor=\"Conclusion\"", "w:anchor=\"Missing\""),
            Triple(document, "w:anchor=\"Conclusion\"", "w:anchor=\"../outside\""),
            Triple(document, "w:name=\"Conclusion\"", "w:name=\"space name\""),
            Triple(document, "<w:bookmarkEnd w:id=\"1\"/>", "<w:bookmarkEnd w:id=\"2\"/>"),
            Triple(document, "<w:bookmarkEnd w:id=\"1\"/>", ""),
            Triple(document, "<w:u w:val=\"single\"/>", "<w:u w:val=\"double\"/>"),
            Triple(document, "<w:hyperlink r:id=\"mail\">", "<w:hyperlink r:id=\"mail\" w:anchor=\"Conclusion\">"))) {
            assertThrows(IllegalArgumentException::class.java) {
                PdfEngine().render(OwnEngineDocxReader().read(rewrite(part, old, replacement)), fonts())
            }
        }
    }

    @Test fun `registered provider preserves inert navigation and independently validates all archival profiles`() {
        val validator = PdfProcessValidator(Path.of(System.getProperty("nuaforma.pdf.validation.java")),
            Files.list(Path.of(System.getProperty("nuaforma.pdf.validation"))).use { paths -> paths.filter { it.toString().endsWith(".jar") }.sorted().toList() }, temporary)
        val provider = DocxPdfProvider(NativePdfConfiguration(fonts().map { NativePdfFont("Liberation Sans", it.id, it.bytes()) }, validator))
        for (kind in kinds) for (profile in listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b")) {
            val output = ByteArrayOutputStream()
            provider.generate(GenerationRequest(source(kind).inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile)))), output, GenerationContext())
            Loader.loadPDF(output.toByteArray()).use { pdf ->
                assertEquals(pdf.numberOfPages - 1, pdf.documentCatalog.names.dests.names.getValue("Conclusion").retrievePageNumber())
                assertEquals("Conclusion", pdf.documentCatalog.documentOutline.firstChild.title)
                assertTrue(pdf.pages.any { it.annotations.isNotEmpty() }, "$kind $profile")
                assertTrue(PDFTextStripper().getText(pdf).contains("N099"))
            }
        }
        assertEquals(0L, Files.list(temporary).use { it.count() })
    }
}
