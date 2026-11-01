package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.pdf.PdfProcessValidator
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.*
import javax.imageio.ImageIO

/** Reproducible source builder for the initial, reviewed diagnostic PDF golden images. */
class TemplateDiagnosticPdfTest {
    @TempDir lateinit var temporary: Path
    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes() }
    private fun provider(): DocxPdfProvider {
        val fonts = listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic", "boldItalic" to "BoldItalic").map { (face, file) ->
            NativePdfFont("Liberation Sans", face, resource("/own-engine-fonts/LiberationSans-$file.ttf"))
        }
        val validator = PdfProcessValidator(Path.of(System.getProperty("nuaforma.pdf.validation.java")),
            Files.list(Path.of(System.getProperty("nuaforma.pdf.validation"))).use { paths -> paths.filter { it.toString().endsWith(".jar") }.sorted().toList() }, temporary)
        return DocxPdfProvider(NativePdfConfiguration(fonts, validator))
    }
    private fun template(marker: String, letter: Boolean = false): ByteArray = ByteArrayOutputStream().also { target ->
        ZipOutputStream(target).use { output -> ZipInputStream(resource("/native-profile/word-letter.docx").inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val bytes = input.readBytes()
                output.putNextEntry(ZipEntry(entry.name))
                val xml = if (entry.name == "word/document.xml") bytes.decodeToString().replace("<w:body>",
                    "<w:body><w:p><w:r><w:rPr><w:rFonts w:ascii=\"Liberation Sans\" w:hAnsi=\"Liberation Sans\"/></w:rPr><w:t>$marker</w:t></w:r></w:p>") else null
                output.write(if (xml == null) bytes else (if (letter) xml.replace(Regex("<w:pgSz[^>]*/>"), "<w:pgSz w:w=\"12240\" w:h=\"15840\"/>") else xml).toByteArray())
                output.closeEntry()
            }
        } }
    }.toByteArray()
    private fun request(marker: String) = GenerationRequest(template(marker).inputStream(),
        GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.PDF, mapOf("profile" to "pdfa-1b"))),
        mapOf("name" to "PRIVATE_DATA_MUST_NOT_BE_INSERTED"))

    @Test fun `passive Word markers preserve actual generated PDF text and pixels`() {
        val split = "{na</w:t></w:r><w:bookmarkStart w:id=\"77\" w:name=\"split\"/>" +
            "<w:proofErr w:type=\"spellStart\"/><w:r><w:rPr><w:rFonts w:ascii=\"Liberation Sans\" w:hAnsi=\"Liberation Sans\"/></w:rPr>" +
            "<w:t>me</w:t><w:lastRenderedPageBreak/><w:t>}</w:t></w:r>" +
            "<w:proofErr w:type=\"spellEnd\"/><w:bookmarkEnd w:id=\"77\"/>" +
            "<w:r><w:rPr><w:rFonts w:ascii=\"Liberation Sans\" w:hAnsi=\"Liberation Sans\"/></w:rPr><w:t>"
        val engine = provider()
        fun generate(bytes: ByteArray): ByteArray {
            val output = ByteArrayOutputStream()
            try {
                engine.generate(GenerationRequest(bytes.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                    FormatRequest(DocumentFormat.PDF, mapOf("profile" to "standard"))), mapOf("name" to "Ada")), output,
                    GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
            } catch (error: GenerationException) {
                fail<Nothing>(error.diagnostics.joinToString { "${it.rejectionReason}: ${it.sourceContext?.explanation}" }, error)
            }
            return output.toByteArray()
        }
        val mixed = "{na</w:t><w:lastRenderedPageBreak/><w:t>me}</w:t><w:tab/><w:t>Tail"
        val literal = "Ada</w:t><w:tab/><w:t>Tail"
        for ((input, control) in listOf(split to "Ada", mixed to literal)) {
            Loader.loadPDF(generate(template(control))).use { expected ->
                Loader.loadPDF(generate(template(input))).use { actual ->
                    assertEquals(PDFTextStripper().getText(expected), PDFTextStripper().getText(actual))
                    assertEquals(expected.numberOfPages, actual.numberOfPages)
                    for (page in 0 until actual.numberOfPages) {
                        val before = PDFRenderer(expected).renderImageWithDPI(page, 96f)
                        val after = PDFRenderer(actual).renderImageWithDPI(page, 96f)
                        assertImagesMatch(before, after, "Passive Word markers page ${page + 1}") {
                            pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                        }
                    }
                }
            }
        }
    }

    @Test fun `validated literal diagnostic PDF marks original content and matches reviewed page images`() {
        val output = ByteArrayOutputStream()
        val result = provider().diagnoseTemplate(request("Prefix {broken"), output, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
        assertEquals("standard", result.output.options["profile"])
        val directory = Files.createDirectories(Path.of("build/150-diagnostic-golden"))
        Files.write(directory.resolve("diagnostic.pdf"), output.toByteArray())
        Loader.loadPDF(output.toByteArray()).use { pdf ->
            val text = PDFTextStripper().getText(pdf)
            assertTrue(text.contains("Prefix [ERROR 1] {broken"), text)
            assertTrue(text.contains("Generation failed."), text)
            assertTrue(text.contains("UNCLOSED_TEMPLATE_MARKER"), text)
            assertFalse(text.contains("PRIVATE_DATA_MUST_NOT_BE_INSERTED"))
            assertEquals(2, pdf.numberOfPages)
            val renderer = PDFRenderer(pdf)
            val actual = (0 until pdf.numberOfPages).map { page ->
                assertTrue(pdf.getPage(page).resources.fontNames.all { pdf.getPage(page).resources.getFont(it).isEmbedded })
                renderer.renderImageWithDPI(page, 96f).also { ImageIO.write(it, "png", directory.resolve("page-${page + 1}.png").toFile()) }
            }
            actual.forEachIndexed { page, image ->
                val expected = javaClass.getResourceAsStream("/template-diagnostics/page-${page + 1}.png")
                assertNotNull(expected, "Review the initial build/150-diagnostic-golden candidate; tests never create or overwrite golden resources")
                expected!!.use {
                    assertImagesMatch(ImageIO.read(it), image, "Diagnostic page ${page + 1}") {
                        pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                    }
                }
            }
        }
    }

    @Test fun `production rejects diagnostics and cancellation publishes no bytes`() {
        val provider = provider()
        for (context in listOf(GenerationContext(), GenerationContext(GenerationLimits(), CancellationToken { true }, null,
            java.time.Clock.systemUTC(), GenerationLogging(LoggingMode.DEVELOPMENT)))) {
            val output = ByteArrayOutputStream()
            assertThrows(GenerationException::class.java) { provider.diagnoseTemplate(request("{broken"), output, context) }
            assertEquals(0, output.size())
        }
        val output = ByteArrayOutputStream()
        val unexpected = GenerationRequest(object : java.io.InputStream() { override fun read(): Int = error("Must not read template") },
            GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.PDF, mapOf("unknown" to true))))
        val rejected = assertThrows(GenerationException::class.java) {
            provider.diagnoseTemplate(unexpected, output, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
        }
        assertEquals(RejectionReason.UNRECOGNIZED_PDF_OPTIONS, rejected.diagnostics.first().rejectionReason)
        assertEquals(0, output.size())
    }

    @Test fun `diagnostic pages respect US Letter and preserve literal markers`() {
        val output = ByteArrayOutputStream()
        provider().diagnoseTemplate(GenerationRequest(template("Prefix {trim(name,)}", true).inputStream(),
            GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.PDF))), output,
            GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
        Loader.loadPDF(output.toByteArray()).use { pdf ->
            for (page in pdf.pages) {
                assertEquals(612f, page.mediaBox.width, 0.1f)
                assertEquals(792f, page.mediaBox.height, 0.1f)
            }
            val text = PDFTextStripper().getText(pdf)
            assertTrue(text.contains("UNEXPECTED_EXPRESSION_TOKEN"))
            assertTrue(text.contains("{trim(name,"))
        }
    }
    @Test fun `hidden invalid markers and unavailable fonts are omitted before validated PDF rendering`() {
        fun withHidden(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { target ->
            ZipOutputStream(target).use { zip -> ZipInputStream(bytes.inputStream()).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val original = input.readBytes()
                    zip.putNextEntry(ZipEntry(entry.name))
                    val changed = if (entry.name == "word/document.xml") original.decodeToString()
                        .replaceFirst("<w:rPr>", "<w:rPr><w:vanish w:val=\"false\"/><w:webHidden/>")
                        .replaceFirst("</w:t></w:r></w:p>",
                        "</w:t></w:r><w:r><w:rPr><w:vanish/><w:rFonts w:ascii=\"Missing Hidden Font\" w:hAnsi=\"Missing Hidden Font\"/></w:rPr><w:t>{HIDDEN_BAD(</w:t></w:r></w:p>").toByteArray() else original
                    zip.write(changed); zip.closeEntry()
                }
            } }
        }.toByteArray()
        val requirements = GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.PDF))
        fun generate(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also {
            provider().generate(GenerationRequest(bytes.inputStream(), requirements, mapOf("name" to "Ada")), it, GenerationContext())
        }.toByteArray()
        val ordinary = template("Visible {name}")
        val expected = generate(ordinary)
        val actual = generate(withHidden(ordinary))
        val directory = Files.createDirectories(Path.of("build/154-hidden-text"))
        Files.write(directory.resolve("visible.pdf"), actual)
        Loader.loadPDF(expected).use { baseline -> Loader.loadPDF(actual).use { pdf ->
            val text = PDFTextStripper().getText(pdf)
            assertTrue(text.contains("Visible Ada"), text)
            assertFalse(text.contains("HIDDEN_BAD"), text)
            assertEquals(baseline.numberOfPages, pdf.numberOfPages)
            val left = PDFRenderer(baseline); val right = PDFRenderer(pdf)
            for (page in 0 until pdf.numberOfPages) {
                val image = right.renderImageWithDPI(page, 96f)
                ImageIO.write(image, "png", directory.resolve("page-${page + 1}.png").toFile())
                assertImagesMatch(left.renderImageWithDPI(page, 96f), image, "Hidden text page ${page + 1}") {
                    pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                }
            }
        } }
        val diagnostic = ByteArrayOutputStream()
        provider().diagnoseTemplate(GenerationRequest(withHidden(template("Visible {broken")).inputStream(), requirements), diagnostic,
            GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
        Loader.loadPDF(diagnostic.toByteArray()).use { pdf ->
            val text = PDFTextStripper().getText(pdf)
            assertTrue(text.contains("[ERROR 1] {broken"), text)
            assertFalse(text.contains("HIDDEN_BAD"), text)
        }
    }

    @Test fun `unsupported paragraph shading has concrete coordinates and a marked DOCX despite rejected PDF`() {
        val raw = template("Source paragraph")
        val modified = ByteArrayOutputStream().also { target -> ZipOutputStream(target).use { zip ->
            ZipInputStream(raw.inputStream()).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val bytes = input.readBytes()
                    zip.putNextEntry(ZipEntry(entry.name))
                    zip.write(if (entry.name == "word/document.xml") bytes.decodeToString().replaceFirst("<w:p>",
                        "<w:p><w:pPr><w:shd w:fill=\"008B90\"/></w:pPr>").toByteArray() else bytes)
                    zip.closeEntry()
                }
            }
        } }.toByteArray()
        val request = { GenerationRequest(modified.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
            FormatRequest(DocumentFormat.PDF, mapOf("profile" to "standard")))) }
        val development = GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))
        val error = assertThrows(GenerationException::class.java) { provider().generate(request(), ByteArrayOutputStream(), development) }
        val diagnostic = error.diagnostics.first()
        assertEquals(RejectionReason.NATIVE_DOCX_PROFILE_UNSUPPORTED, diagnostic.rejectionReason)
        assertEquals("paragraph:1", diagnostic.documentLocation)
        assertEquals("word/document.xml", diagnostic.part)
        assertEquals("w:shd", diagnostic.sourceContext?.construct)
        assertTrue(diagnostic.sourceContext!!.path?.endsWith("/w:p[1]/w:pPr[1]/w:shd[1]") == true)
        assertEquals("Source paragraph", diagnostic.sourceContext!!.excerpt)
        val output = ByteArrayOutputStream()
        val result = provider().diagnoseDocument(request(), output, development, false)
        assertEquals(DocumentFormat.DOCX, result.output.format)
        Files.write(Files.createDirectories(Path.of("build/170-inspection")).resolve("unsupported-shading.docx"), output.toByteArray())
        val xml = ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.map { it.name to zip.readBytes().decodeToString() }.toMap().getValue("word/document.xml")
        }
        assertTrue(xml.contains("[ERROR 1]"), xml)
        assertTrue(xml.contains("Source paragraph"), xml)
        assertTrue(xml.contains("shd"), xml)
    }

    @Test fun `DOCX preview needs neither PDF validator nor fonts and remains development only bounded and private`() {
        val provider = DocxPdfProvider(NativePdfConfiguration(emptyList()))
        val development = GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))
        val output = ByteArrayOutputStream()
        provider.diagnoseDocument(request("Hello {name}</w:t></w:r></w:p><w:p><w:r><w:t>{broken"), output, development, true)
        assertTrue(output.size() > 0)
        val previews = Files.createDirectories(Path.of("build/170-inspection"))
        Files.write(previews.resolve("preview.docx"), output.toByteArray())
        val fallback = ByteArrayOutputStream()
        provider.diagnoseDocument(request("{name}"), fallback, development, false)
        assertTrue(fallback.size() > 0)
        for ((context, expected) in listOf(
            GenerationContext() to DiagnosticCodes.UNSUPPORTED_OPTION,
            GenerationContext(GenerationLimits(maxTemplateBytes = 1), CancellationToken.NONE, null, java.time.Clock.systemUTC(), GenerationLogging(LoggingMode.DEVELOPMENT)) to DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED,
            GenerationContext(GenerationLimits(), CancellationToken { true }, null, java.time.Clock.systemUTC(), GenerationLogging(LoggingMode.DEVELOPMENT)) to DiagnosticCodes.CANCELLED,
            GenerationContext(GenerationLimits(maxOutputBytes = 1), CancellationToken.NONE, null, java.time.Clock.systemUTC(), GenerationLogging(LoggingMode.DEVELOPMENT)) to DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED
        )) {
            val bytes = ByteArrayOutputStream()
            val error = assertThrows(GenerationException::class.java) { provider.diagnoseDocument(request("{broken"), bytes, context, true) }
            assertEquals(expected, error.diagnostics.first().code)
            assertEquals(0, bytes.size())
        }
        val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            executor.invokeAll((1..4).map { id -> java.util.concurrent.Callable {
                val bytes = ByteArrayOutputStream()
                provider.diagnoseDocument(request("{broken$id"), bytes, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)), true)
                val xml = ZipInputStream(bytes.toByteArray().inputStream()).use { zip -> generateSequence { zip.nextEntry }
                    .map { it.name to zip.readBytes().decodeToString() }.toMap().getValue("word/document.xml") }
                assertTrue(xml.contains("{broken$id"))
                for (other in 1..4) if (id != other) assertFalse(xml.contains("{broken$other"))
            } }).forEach { it.get() }
        } finally { executor.shutdownNow() }
    }

    @Test fun `failure inspection refuses to label a successful document as failed`() {
        val output = ByteArrayOutputStream()
        val request = GenerationRequest(template("Hello {name}").inputStream(),
            GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.PDF, mapOf("profile" to "standard"))), mapOf("name" to "Ada"))
        val error = assertThrows(GenerationException::class.java) {
            provider().diagnoseDocument(request, output, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)), false)
        }
        assertEquals(RejectionReason.DOCUMENT_DIAGNOSTIC_REQUIRES_FAILURE, error.diagnostics.first().rejectionReason)
        assertEquals(0, output.size())
    }

}
