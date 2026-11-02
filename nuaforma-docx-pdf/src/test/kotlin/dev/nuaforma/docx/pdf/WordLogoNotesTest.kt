package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.pdf.PdfProcessValidator
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.Color
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class WordLogoNotesTest {
    @TempDir lateinit var temporary: Path

    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes() }

    private fun provider(): NativeDocxPdfProvider {
        val fonts = listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic", "boldItalic" to "BoldItalic").map { (face, file) ->
            NativePdfFont("Liberation Sans", face, resource("/own-engine-fonts/LiberationSans-$file.ttf"))
        }
        val validator = PdfProcessValidator(Path.of(System.getProperty("nuaforma.pdf.validation.java")),
            Files.list(Path.of(System.getProperty("nuaforma.pdf.validation"))).use { files -> files.filter { it.toString().endsWith(".jar") }.sorted().toList() }, temporary)
        return NativeDocxPdfProvider(NativePdfConfiguration(fonts, validator))
    }

    private fun repack(source: ByteArray, change: (String, ByteArray) -> ByteArray): ByteArray = ByteArrayOutputStream().also { result ->
        ZipOutputStream(result).use { output -> ZipInputStream(source.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                output.putNextEntry(ZipEntry(entry.name))
                output.write(change(entry.name, input.readBytes()))
                output.closeEntry()
            }
        } }
    }.toByteArray()

    @Test
    fun `unsupported EMF cleanup removes the picture instead of the footer and footnotes`() {
        val source = repack(resource("/word-compatibility/a4/source.docx")) { name, bytes ->
            if (name != "word/media/footer.emf") bytes else dev.nuaforma.docx.pdf.adapters.nativeengine.EmfFixture.file(
                dev.nuaforma.docx.pdf.adapters.nativeengine.EmfFixture.record(105, 0))
        }
        val output = ByteArrayOutputStream()
        val report = provider().cleanTemplate(source.inputStream(), output, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
        assertTrue(report.changes.any { it.part == "word/media/footer.emf" })
        assertFalse(report.changes.any { it.part == "word/footer1.xml" && it.action == TemplateCleanupAction.REMOVE_PART })
        val parts = mutableMapOf<String, ByteArray>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; parts[entry.name] = zip.readBytes() }
        }
        assertTrue(parts.getValue("word/footer1.xml").decodeToString().contains("FOOTER logo retained"))
        assertTrue(parts.containsKey("word/footnotes.xml"))
        assertTrue(parts.containsKey("word/media/header.emf"))
    }

    @Test
    fun `header and smaller footer EMF logos survive cleanup and PDF generation with real footnotes`() {
        val provider = provider()
        for (kind in listOf("a4", "letter")) {
            val source = resource("/word-compatibility/$kind/source.docx")
            val cleaned = ByteArrayOutputStream()
            val report = provider.cleanTemplate(source.inputStream(), cleaned, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
            assertTrue(report.changes.isEmpty(), report.changes.toString())
            assertArrayEquals(source, cleaned.toByteArray())
            val output = ByteArrayOutputStream()
            provider.generate(GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                FormatRequest(DocumentFormat.PDF, mapOf("profile" to "standard"))), emptyMap<String, Any?>()), output, GenerationContext())
            val artifacts = Files.createDirectories(Path.of("build/word-compatibility-reference/$kind"))
            Files.write(artifacts.resolve("actual.pdf"), output.toByteArray())
            Loader.loadPDF(output.toByteArray()).use { pdf ->
                assertTrue(pdf.numberOfPages >= 2)
                val text = PDFTextStripper().getText(pdf)
                assertTrue(text.contains("Een korte toelichting bij de eerste verwijzing."))
                assertTrue(text.contains("END De laatste alinea"))
                assertTrue(text.contains("SIGNED INDENTS"))
                for (page in pdf.pages) {
                    val images = page.resources.xObjectNames.map { page.resources.getXObject(it) }.filterIsInstance<PDImageXObject>()
                    assertTrue(images.any { it.width == 400 && it.height == 200 }, "large header logo")
                    assertTrue(images.any { it.width == 160 && it.height == 80 }, "small footer logo")
                }
                for (index in 0 until pdf.numberOfPages) {
                    val raster = PDFRenderer(pdf).renderImageWithDPI(index, 72f)
                    assertEquals(Color(16, 112, 176).rgb, raster.getRGB(130, 30), "header logo ink on page ${index + 1}")
                    assertEquals(Color(16, 112, 176).rgb, raster.getRGB(88, raster.height - 52), "footer logo ink on page ${index + 1}")
                }
                assertEquals(pdf.numberOfPages, Regex("HEADER logo retained").findAll(text).count())
                assertEquals(pdf.numberOfPages, Regex("FOOTER logo retained").findAll(text).count())
                assertEquals(if (kind == "letter") 612f else 595.3f, pdf.getPage(0).mediaBox.width, .2f)
            }
        }
    }

    @Test
    fun `EMF transparency passes archival two and three but one refuses without output`() {
        val provider = provider()
        val source = resource("/word-compatibility/a4/source.docx")
        for (profile in listOf("pdfa-1b", "pdfa-2b", "pdfa-3b")) {
            val request = GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile))), emptyMap<String, Any?>())
            val output = ByteArrayOutputStream()
            if (profile == "pdfa-1b") {
                val error = assertThrows(GenerationException::class.java) { provider.generate(request, output, GenerationContext()) }
                assertTrue(error.diagnostics.any { it.code == DiagnosticCodes.UNSUPPORTED_INPUT })
                assertEquals(0, output.size())
            } else {
                provider.generate(request, output, GenerationContext())
                Loader.loadPDF(output.toByteArray()).use { pdf ->
                    assertEquals(2, pdf.numberOfPages)
                    assertTrue(PDFTextStripper().getText(pdf).contains("FOOTER logo retained"))
                }
            }
        }
    }

    @Test
    fun `EMF logo text uses configured physical font bytes through the real provider`() {
        val source = repack(resource("/word-compatibility/a4/source.docx")) { name, bytes ->
            if (name != "word/media/header.emf") bytes else dev.nuaforma.docx.pdf.adapters.nativeengine.EmfFixture.file(
                dev.nuaforma.docx.pdf.adapters.nativeengine.EmfFixture.font(),
                dev.nuaforma.docx.pdf.adapters.nativeengine.EmfFixture.record(37, 1),
                dev.nuaforma.docx.pdf.adapters.nativeengine.EmfFixture.record(18, 1),
                dev.nuaforma.docx.pdf.adapters.nativeengine.EmfFixture.text("Logo"))
        }
        val output = ByteArrayOutputStream()
        provider().generate(GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
            FormatRequest(DocumentFormat.PDF, mapOf("profile" to "standard"))), emptyMap<String, Any?>()), output, GenerationContext())
        Loader.loadPDF(output.toByteArray()).use { pdf ->
            val image = pdf.getPage(0).resources.xObjectNames.map { pdf.getPage(0).resources.getXObject(it) }
                .filterIsInstance<PDImageXObject>().single { it.width == 400 }.image
            val alpha = image.getRGB(0, 0, image.width, image.height, null, 0, image.width).map { it ushr 24 }
            assertTrue(alpha.any { it == 255 }, "The supplied font paints actual glyphs")
            assertTrue(alpha.any { it == 0 }, "The logo background remains transparent")
            assertTrue(PDFTextStripper().getText(pdf).contains("HEADER logo retained"))
        }
    }

    @Test
    fun `explicitly opaque EMF artwork passes archival one without flattening transparent sources`() {
        val source = repack(resource("/word-compatibility/a4/source.docx")) { name, bytes ->
            if (!name.endsWith(".emf")) bytes else dev.nuaforma.docx.pdf.adapters.nativeengine.EmfFixture.file(
                dev.nuaforma.docx.pdf.adapters.nativeengine.EmfFixture.record(43, 0, 0, 200, 100))
        }
        val output = ByteArrayOutputStream()
        provider().generate(GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
            FormatRequest(DocumentFormat.PDF, mapOf("profile" to "pdfa-1b"))), emptyMap<String, Any?>()), output, GenerationContext())
        Loader.loadPDF(output.toByteArray()).use { pdf ->
            assertEquals(1.4f, pdf.version)
            assertEquals(2, pdf.numberOfPages)
            val images = pdf.getPage(0).resources.xObjectNames.map { pdf.getPage(0).resources.getXObject(it) }.filterIsInstance<PDImageXObject>()
            assertTrue(images.size >= 2)
            assertTrue(images.all { it.softMask == null })
        }
    }
}
