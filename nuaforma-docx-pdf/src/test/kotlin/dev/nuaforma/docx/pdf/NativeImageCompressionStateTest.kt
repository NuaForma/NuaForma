package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import dev.nuaforma.pdf.PdfProcessValidator
import org.apache.pdfbox.Loader
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.graphics.image.PDImage
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.*
import javax.imageio.ImageIO

class NativeImageCompressionStateTest {
    @TempDir lateinit var temporary: Path

    private fun bytes(path: String) = requireNotNull(javaClass.getResourceAsStream(path)).use { it.readBytes() }

    private fun hash(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }

    private fun text(document: PDDocument) = PDFTextStripper().getText(document).replace(Regex("\\s+"), " ").trim()

    private fun configured(): DocxPdfProvider {
        val fonts = listOf("Regular" to "regular", "Bold" to "bold", "Italic" to "italic", "BoldItalic" to "boldItalic").map { (file, face) ->
            NativePdfFont("Liberation Sans", face, bytes("/own-engine-fonts/LiberationSans-$file.ttf"))
        }
        val directory = Path.of(System.getProperty("nuaforma.pdf.validation"))
        val classpath = Files.list(directory).use { paths -> paths.filter { it.toString().endsWith(".jar") }.sorted().toList() }
        val validator = PdfProcessValidator(Path.of(System.getProperty("nuaforma.pdf.validation.java")), classpath, temporary)
        return DocxPdfProvider(NativePdfConfiguration(fonts, validator))
    }

    private fun generate(provider: DocxPdfProvider, source: ByteArray, profile: String, output: ByteArrayOutputStream = ByteArrayOutputStream()): ByteArray {
        provider.generate(GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
            FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile)))), output,
            GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
        return output.toByteArray()
    }

    private class Images(page: PDPage) : PDFGraphicsStreamEngine(page) {
        val placements = mutableListOf<Rectangle2D>()

        override fun drawImage(image: PDImage) {
            placements += graphicsState.currentTransformationMatrix.createAffineTransform()
                .createTransformedShape(Rectangle2D.Float(0f, 0f, 1f, 1f)).bounds2D
        }

        override fun appendRectangle(a: Point2D, b: Point2D, c: Point2D, d: Point2D) {}

        override fun clip(rule: Int) {}

        override fun moveTo(x: Float, y: Float) {}

        override fun lineTo(x: Float, y: Float) {}

        override fun curveTo(a: Float, b: Float, c: Float, d: Float, e: Float, f: Float) {}

        override fun getCurrentPoint(): Point2D = Point2D.Float()

        override fun closePath() {}

        override fun endPath() {}

        override fun strokePath() {}

        override fun fillPath(rule: Int) {}

        override fun fillAndStrokePath(rule: Int) {}

        override fun shadingFill(name: COSName) {}

    }

    private fun placements(page: PDPage): List<Rectangle2D> = Images(page).also { it.processPage(page) }.placements
        .sortedWith(compareByDescending<Rectangle2D> { it.y }.thenBy { it.x })

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `compression hints preserve all body header and footer images across validated PDF profiles`(paper: String) {
        val folder = "/image-compression-states/$paper/"
        val source = bytes(folder + "source.docx")
        val control = bytes(folder + "control-source.docx")
        val reference = bytes(folder + "word-reference.pdf")
        val controlReference = bytes(folder + "control-word-reference.pdf")
        val golden = bytes(folder + "page-1.png")
        val proof = bytes(folder + "golden-provenance.json").decodeToString()
        for (value in listOf(source, control, reference, controlReference, golden)) assertTrue(proof.contains(hash(value)))
        assertTrue(bytes(folder + "word-reference.pdf.provenance.json").decodeToString().contains(hash(source)))
        assertTrue(bytes(folder + "control-word-reference.pdf.provenance.json").decodeToString().contains(hash(control)))
        val provider = configured()
        val clean = ByteArrayOutputStream()
        assertTrue(provider.cleanTemplate(source.inputStream(), clean, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))).changes.isEmpty())
        assertArrayEquals(source, clean.toByteArray())
        val output = Files.createDirectories(Path.of("build/262-image-compression-states"))
        Loader.loadPDF(reference).use { word ->
            Loader.loadPDF(controlReference).use { plainWord ->
                assertEquals(1, word.numberOfPages)
                assertImagesMatch(PDFRenderer(plainWord).renderImageWithDPI(0, 96f), PDFRenderer(word).renderImageWithDPI(0, 96f)) {
                    pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                }
            }
            val expected = placements(word.getPage(0))
            assertEquals(7, expected.size)
            for (profile in listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b")) {
                val rendered = generate(provider, source, profile)
                Files.write(output.resolve("$paper-$profile.pdf"), rendered)
                Loader.loadPDF(rendered).use { actual ->
                    assertEquals(1, actual.numberOfPages)
                    assertEquals(text(word), text(actual))
                    assertEquals(word.getPage(0).mediaBox.width, actual.getPage(0).mediaBox.width, .1f)
                    assertEquals(word.getPage(0).mediaBox.height, actual.getPage(0).mediaBox.height, .1f)
                    val found = placements(actual.getPage(0))
                    assertEquals(7, found.size)
                    for ((before, after) in expected.zip(found)) {
                        assertEquals(before.x, after.x, .03)
                        assertEquals(before.y, after.y, .03)
                        assertEquals(before.width, after.width, .03)
                        assertEquals(before.height, after.height, .03)
                    }
                    assertTrue(actual.getPage(0).resources.fontNames.all { actual.getPage(0).resources.getFont(it).isEmbedded })
                    assertImagesMatch(ImageIO.read(golden.inputStream()), PDFRenderer(actual).renderImageWithDPI(0, 96f)) {
                        pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                    }
                }
            }
        }
    }

    private fun changed(source: ByteArray, part: String, replacement: String): ByteArray = ByteArrayOutputStream().also { result ->
        ZipOutputStream(result).use { output -> ZipInputStream(source.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val original = input.readBytes()
                val changed = if (entry.name == part) {
                    val before = original.decodeToString()
                    val after = Regex("cstate=\"[^\"]*\"").replaceFirst(before, replacement)
                    assertNotEquals(before, after)
                    after.toByteArray()
                } else original
                output.putNextEntry(ZipEntry(entry.name)); output.write(changed); output.closeEntry()
            }
        } }
    }.toByteArray()

    @Test
    fun `invalid original image attributes fail atomically with their exact story and source path`() {
        val provider = configured()
        val source = bytes("/image-compression-states/a4/source.docx")
        for (part in listOf("word/document.xml", "word/header1.xml", "word/footer1.xml")) {
            for (replacement in listOf("cstate=\"unknown\"", "cstate=\"Print\"", "cstate=\"\"", "a:cstate=\"print\"",
                "cstate=\"print\" extra=\"1\"", "cstate=\"print\" r:link=\"external\"")) {
                val output = ByteArrayOutputStream()
                val failure = assertThrows(GenerationException::class.java) { generate(provider, changed(source, part, replacement), "standard", output) }
                assertEquals(0, output.size())
                val diagnostic = failure.diagnostics.single()
                assertEquals(part, diagnostic.part)
                assertTrue(diagnostic.sourceContext?.path.orEmpty().endsWith("blip[1]"))
                assertEquals("Unsupported original embedded image attribute or compression state", diagnostic.sourceContext?.explanation)
            }
        }
    }

}
