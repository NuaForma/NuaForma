package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import dev.nuaforma.pdf.PdfProcessValidator
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

class NativePackageTrashTest {
    @TempDir lateinit var temporary: Path

    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes() }

    private fun provider(): DocxPdfProvider {
        val fonts = listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic", "boldItalic" to "BoldItalic").map { (face, name) ->
            NativePdfFont("Liberation Sans", face, resource("/own-engine-fonts/LiberationSans-$name.ttf"))
        }
        return DocxPdfProvider(NativePdfConfiguration(fonts, PdfProcessValidator(
            Path.of(System.getProperty("nuaforma.pdf.validation.java")),
            Files.list(Path.of(System.getProperty("nuaforma.pdf.validation"))).use { paths -> paths.filter { it.toString().endsWith(".jar") }.sorted().toList() }, temporary)))
    }

    private fun parts(name: String = "a4") = PdfFixture.unzip(resource("/theme-colors/$name/source.docx")).toMutableMap().apply {
        put("[trash]/0000.dat", byteArrayOf(0, -1) + "{UNFINISHED<not-xml".toByteArray())
        put("[trash]/00aF.dat", ByteArray(256) { it.toByte() })
    }

    private fun request(source: ByteArray, profile: String = "standard") = GenerationRequest(source.inputStream(),
        GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile))))

    private fun text(pdf: org.apache.pdfbox.pdmodel.PDDocument, page: Int) = PDFTextStripper().also { it.startPage = page; it.endPage = page }
        .getText(pdf).replace(Regex("\\s+"), " ").trim()

    @Test
    fun `discarded physical items preserve all qualified A4 and Letter Word pages`() {
        val provider = provider()
        for (name in listOf("a4", "letter")) {
            val source = PdfFixture.zip(parts(name))
            val original = source.copyOf()
            val cleaned = ByteArrayOutputStream()
            assertTrue(provider.cleanTemplate(source.inputStream(), cleaned,
                GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))).changes.isEmpty())
            assertArrayEquals(source, cleaned.toByteArray())
            Loader.loadPDF(resource("/theme-colors/$name/word-reference.pdf")).use { word ->
                for (profile in listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b")) {
                    val output = ByteArrayOutputStream()
                    provider.generate(request(source, profile), output, GenerationContext())
                    Loader.loadPDF(output.toByteArray()).use { actual ->
                        assertEquals(word.numberOfPages, actual.numberOfPages)
                        for (page in 0 until word.numberOfPages) {
                            assertEquals(text(word, page + 1), text(actual, page + 1))
                            assertEquals(word.getPage(page).mediaBox.width, actual.getPage(page).mediaBox.width, .1f)
                            assertEquals(word.getPage(page).mediaBox.height, actual.getPage(page).mediaBox.height, .1f)
                            assertTrue(actual.getPage(page).resources.fontNames.all { actual.getPage(page).resources.getFont(it).isEmbedded })
                            assertImagesMatch(ImageIO.read(resource("/theme-colors/$name/page-${page + 1}.png").inputStream()),
                                PDFRenderer(actual).renderImageWithDPI(page, 96f)) {
                                pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                            }
                        }
                    }
                }
            }
            assertArrayEquals(original, source)
        }
    }

    @Test
    fun `typed referenced and oversized discarded items remain rejected without partial PDF`() {
        val provider = provider()
        val variants = listOf(
            parts().apply { put("[Content_Types].xml", getValue("[Content_Types].xml").decodeToString().replace("</Types>",
                "<Default Extension=\"dat\" ContentType=\"application/octet-stream\"/></Types>").toByteArray()) },
            parts().apply { put("[Content_Types].xml", getValue("[Content_Types].xml").decodeToString().replace("</Types>",
                "<Override PartName=\"/[trash]/0000.dat\" ContentType=\"application/octet-stream\"/></Types>").toByteArray()) },
            parts().apply { put("word/_rels/document.xml.rels", getValue("word/_rels/document.xml.rels").decodeToString().replace("</Relationships>",
                "<Relationship Id=\"trash\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"../%5Btrash%5D/0000.dat\"/></Relationships>").toByteArray()) })
        for (parts in variants) {
            val output = ByteArrayOutputStream()
            val error = assertThrows(GenerationException::class.java) { provider.generate(request(PdfFixture.zip(parts)), output, GenerationContext()) }
            assertEquals(DiagnosticCodes.INVALID_TEMPLATE, error.diagnostics.single().code)
            assertEquals(0, output.size())
        }
        val oversized = PdfFixture.zip(parts().apply { put("[trash]/0001.dat", ByteArray(100_000)) })
        val output = ByteArrayOutputStream()
        val fault = assertThrows(GenerationException::class.java) {
            provider.generate(request(oversized), output, GenerationContext(limits = GenerationLimits(maxExpandedBytes = 50_000)))
        }
        assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, fault.diagnostics.single().code)
        assertEquals(0, output.size())
        for (parts in variants.take(2)) {
            val cleaned = ByteArrayOutputStream()
            val result = provider.cleanTemplate(PdfFixture.zip(parts).inputStream(), cleaned,
                GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
            assertTrue(result.changes.any { it.part == "[Content_Types].xml" })
            val rendered = ByteArrayOutputStream()
            provider.generate(request(cleaned.toByteArray()), rendered, GenerationContext())
            assertTrue(rendered.toByteArray().take(5).toByteArray().decodeToString().startsWith("%PDF-"))
        }
    }
}
