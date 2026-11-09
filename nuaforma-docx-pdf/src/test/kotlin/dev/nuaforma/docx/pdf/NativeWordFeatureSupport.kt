package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import dev.nuaforma.pdf.PdfProcessValidator
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.jupiter.api.Assertions.*
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/** Shared qualification mechanics; each feature owns its independent Word input and expectations. */
internal class NativeWordFeatureSupport(temporary: Path) {
    val provider: DocxPdfProvider

    init {
        val fonts = listOf("Regular" to "regular", "Bold" to "bold", "Italic" to "italic", "BoldItalic" to "boldItalic").map { (file, face) ->
            NativePdfFont("Liberation Sans", face, bytes("/own-engine-fonts/LiberationSans-$file.ttf"))
        }
        val directory = Path.of(System.getProperty("nuaforma.pdf.validation"))
        val classpath = Files.list(directory).use { paths -> paths.filter { it.toString().endsWith(".jar") }.sorted().toList() }
        val validator = PdfProcessValidator(Path.of(System.getProperty("nuaforma.pdf.validation.java")), classpath, temporary)
        provider = DocxPdfProvider(NativePdfConfiguration(fonts, validator))
    }

    fun bytes(path: String) = requireNotNull(javaClass.getResourceAsStream(path)).use { it.readBytes() }

    private fun hash(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }

    fun generate(source: ByteArray, profile: String, output: ByteArrayOutputStream = ByteArrayOutputStream()): ByteArray {
        provider.generate(GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
            FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile)))), output,
            GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
        return output.toByteArray()
    }

    private fun text(document: PDDocument) = PDFTextStripper().apply { sortByPosition = true }.getText(document).replace(Regex("[\\p{Z}\\s]+"), "")

    fun rejectChanged(sourcePath: String, part: String, element: String, transform: (String) -> String) {
        val source = bytes(sourcePath)
        val rewritten = ByteArrayOutputStream()
        var found = false
        ZipOutputStream(rewritten).use { output -> ZipInputStream(source.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val original = input.readBytes()
                val value = if (entry.name == part) {
                    found = true
                    val before = original.decodeToString()
                    val after = transform(before)
                    assertNotEquals(before, after)
                    after.toByteArray()
                } else original
                output.putNextEntry(ZipEntry(entry.name))
                output.write(value)
                output.closeEntry()
            }
        } }
        assertTrue(found)
        val output = ByteArrayOutputStream()
        val failure = assertThrows(GenerationException::class.java) { generate(rewritten.toByteArray(), "standard", output) }
        assertEquals(0, output.size())
        val diagnostic = failure.diagnostics.single()
        assertEquals(part, diagnostic.part)
        assertTrue(diagnostic.sourceContext?.path.orEmpty().contains(element), diagnostic.toString())
    }

    data class Position(val x: Float, val y: Float)

    fun positions(document: PDDocument, pattern: Regex, tails: Boolean = false): Map<String, Position> {
        val lines = sortedMapOf<Int, MutableList<TextPosition>>()
        val stripper = object : PDFTextStripper() {

            override fun processTextPosition(position: TextPosition) {
                repeat(position.unicode.length) { lines.getOrPut(kotlin.math.round(position.yDirAdj * 100).toInt()) { mutableListOf() } += position }
                super.processTextPosition(position)
            }

        }
        stripper.getText(document)
        val result = linkedMapOf<String, Position>()
        for (line in lines.values) {
            line.sortBy { it.xDirAdj }
            val raw = line.joinToString("") { it.unicode }
            for (match in pattern.findAll(raw)) {
                val position = line[match.range.first]
                assertNull(result.put(match.value, Position(position.xDirAdj, position.yDirAdj)))
                if (tails && match.value.startsWith("P")) {
                    val index = raw.indexOf("TAIL", match.range.last + 1)
                    assertTrue(index >= 0, match.value)
                    result[match.value + ":TAIL"] = Position(line[index].xDirAdj, line[index].yDirAdj)
                }
            }
        }
        return result
    }

    fun verify(folder: String, profiles: List<String>, pattern: Regex, markerCount: Int,
               tails: Boolean = false, colors: Boolean = false, golden: String = folder + "page-1.png") {
        val source = bytes(folder + "source.docx")
        val reference = bytes(folder + "word-reference.pdf")
        val image = bytes(golden)
        val proof = bytes(folder + "golden-provenance.json").decodeToString()
        for (value in listOf(source, reference, image)) assertTrue(proof.contains(hash(value)), folder)
        assertTrue(bytes(folder + "word-reference.pdf.provenance.json").decodeToString().contains(hash(source)))
        Loader.loadPDF(reference).use { word ->
            assertEquals(1, word.numberOfPages)
            val expected = positions(word, pattern, tails)
            assertEquals(markerCount, expected.size)
            val wordImage = PDFRenderer(word).renderImageWithDPI(0, 96f)
            for (profile in profiles) Loader.loadPDF(generate(source, profile)).use { actual ->
                assertEquals(1, actual.numberOfPages)
                assertEquals(text(word), text(actual))
                assertEquals(word.getPage(0).mediaBox.width, actual.getPage(0).mediaBox.width, .1f)
                assertEquals(word.getPage(0).mediaBox.height, actual.getPage(0).mediaBox.height, .1f)
                assertTrue(actual.getPage(0).resources.fontNames.all { actual.getPage(0).resources.getFont(it).isEmbedded })
                val found = positions(actual, pattern, tails)
                assertEquals(expected.keys, found.keys)
                val actualImage = PDFRenderer(actual).renderImageWithDPI(0, 96f)
                for ((marker, before) in expected) {
                    val after = found.getValue(marker)
                    assertEquals(before.x, after.x, .21f, marker)
                    assertEquals(before.y, after.y, .21f, marker)
                    if (colors) {

                        fun colorAt(image: java.awt.image.BufferedImage, position: Position) = image.getRGB(
                            kotlin.math.round((position.x + 40) * 96 / 72).toInt(), kotlin.math.round((position.y - 1) * 96 / 72).toInt())

                        assertEquals(colorAt(wordImage, before), colorAt(actualImage, after), marker)
                    }
                }
                assertImagesMatch(ImageIO.read(image.inputStream()), actualImage) {
                    pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                }
            }
        }
    }

}
