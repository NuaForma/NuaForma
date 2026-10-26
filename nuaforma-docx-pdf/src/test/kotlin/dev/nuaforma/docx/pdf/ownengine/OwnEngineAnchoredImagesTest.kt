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

class OwnEngineAnchoredImagesTest {
    @TempDir lateinit var temporary: Path
    private fun source(kind: String) = requireNotNull(javaClass.getResourceAsStream("/own-engine-anchored-images/$kind/source.docx")).use { it.readBytes() }
    private fun fonts() = listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic").map { (id, face) ->
        PdfFont(id, requireNotNull(javaClass.getResourceAsStream("/own-engine-fonts/LiberationSans-$face.ttf")).use { it.readBytes() })
    }
    private fun rewrite(old: String, replacement: String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            ZipInputStream(source("left").inputStream()).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val bytes = input.readBytes()
                    zip.putNextEntry(ZipEntry(entry.name))
                    zip.write(if (entry.name == "word/document.xml") {
                        val text = bytes.decodeToString(); check(text.contains(old)); text.replace(old, replacement).toByteArray()
                    } else bytes)
                    zip.closeEntry()
                }
            }
        }
        return output.toByteArray()
    }

    @Test fun `source anchors cropping and region policies retain all report content and embed the image once`() {
        for (kind in listOf("left", "right", "largest", "top-bottom", "page", "boundary", "letter", "crop", "both-sides", "multipage")) {
            val model = OwnEngineDocxReader().read(source(kind))
            val anchor = model.blocks.filterIsInstance<PdfParagraph>().flatMap { it.anchoredImages }.single()
            assertEquals(if (kind == "page") PdfBoxAnchor.PAGE else PdfBoxAnchor.PARAGRAPH, anchor.anchor)
            assertEquals(if (kind == "crop") .2f else 0f, anchor.image.crop.left)
            Loader.loadPDF(PdfEngine().render(model, fonts())).use { pdf ->
                val text = PDFTextStripper().getText(pdf)
                (1..7).forEach { assertEquals(1, Regex("\\bA%03d\\b".format(java.util.Locale.ROOT, it)).findAll(text).count(), kind) }
                assertEquals(1, (0 until pdf.numberOfPages).sumOf { pdf.getPage(it).resources.xObjectNames.count() }, kind)
                assertEquals(if (kind == "letter") 612f else 595.3f, pdf.getPage(0).mediaBox.width, .01f)
                if (kind == "boundary") {
                    assertTrue(pdf.numberOfPages >= 2)
                    assertEquals(0, pdf.getPage(0).resources.xObjectNames.count())
                    assertFalse(PDFTextStripper().apply { endPage = 1 }.getText(pdf).contains("A001"))
                }
            }
        }
    }

    @Test fun `unqualified wrapping external images flips and crops reject explicitly`() {
        for ((old, replacement) in listOf(
            "wrapSquare" to "wrapTight", "wrapText=\"largest\"" to "wrapText=\"unknown\"",
            "relativeFrom=\"paragraph\"" to "relativeFrom=\"line\"", "behindDoc=\"0\"" to "behindDoc=\"1\"",
            "<a:xfrm>" to "<a:xfrm flipH=\"1\">", "r:embed=\"logo\"" to "r:link=\"logo\"",
            "<a:stretch>" to "<a:srcRect l=\"90000\" r=\"10000\"/><a:stretch>")) {
            assertThrows(IllegalArgumentException::class.java) { OwnEngineDocxReader().read(rewrite(old, replacement)) }
        }
    }

    @Test fun `registered provider preserves floating images through template processing and independently validates archival output`() {
        val validator = PdfProcessValidator(Path.of(System.getProperty("nuaforma.pdf.validation.java")),
            Files.list(Path.of(System.getProperty("nuaforma.pdf.validation"))).use { paths -> paths.filter { it.toString().endsWith(".jar") }.sorted().toList() }, temporary)
        val provider = DocxPdfProvider(NativePdfConfiguration(fonts().map { NativePdfFont("Liberation Sans", it.id, it.bytes()) }, validator))
        for (kind in listOf("left", "both-sides", "crop")) for (profile in listOf("standard", "pdfa-2b", "pdfa-3b")) {
            val output = ByteArrayOutputStream()
            provider.generate(GenerationRequest(source(kind).inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile)))), output, GenerationContext())
            Loader.loadPDF(output.toByteArray()).use { pdf ->
                assertEquals(1, pdf.numberOfPages)
                assertEquals(1, pdf.getPage(0).resources.xObjectNames.count())
                val text = PDFTextStripper().getText(pdf)
                assertTrue(text.contains("A001") && text.contains("A007"))
            }
        }
        assertEquals(0L, Files.list(temporary).use { it.count() })
    }
}
