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

    data class Position(val x: Float, val y: Float, val page: Int)

    fun positions(document: PDDocument, pattern: Regex, tails: Boolean = false): Map<String, Position> {
        val lines = sortedMapOf<Pair<Int, Int>, MutableList<TextPosition>>(compareBy({ it.first }, { it.second }))
        val stripper = object : PDFTextStripper() {

            override fun processTextPosition(position: TextPosition) {
                repeat(position.unicode.length) { lines.getOrPut(currentPageNo to kotlin.math.round(position.yDirAdj * 100).toInt()) { mutableListOf() } += position }
                super.processTextPosition(position)
            }

        }
        stripper.getText(document)
        val result = linkedMapOf<String, Position>()
        for ((lineKey, line) in lines) {
            line.sortBy { it.xDirAdj }
            val raw = line.joinToString("") { it.unicode }
            for (match in pattern.findAll(raw)) {
                val position = line[match.range.first]
                val key = (if (document.numberOfPages > 1) "${lineKey.first}:" else "") + match.value
                assertNull(result.put(key, Position(position.xDirAdj, position.yDirAdj, lineKey.first)))
                if (tails && match.value.startsWith("P")) {
                    val index = raw.indexOf("TAIL", match.range.last + 1)
                    assertTrue(index >= 0, match.value)
                    result[key + ":TAIL"] = Position(line[index].xDirAdj, line[index].yDirAdj, lineKey.first)
                }
            }
        }
        return result
    }

    fun verify(folder: String, profiles: List<String>, pattern: Regex, markerCount: Int,
               tails: Boolean = false, colors: Boolean = false, golden: String = folder + "page-1.png", pageCount: Int = 1) {
        val source = bytes(folder + "source.docx")
        val reference = bytes(folder + "word-reference.pdf")
        val images = (1..pageCount).map { if (it == 1) bytes(golden) else bytes(folder + "page-$it.png") }
        val proof = bytes(folder + "golden-provenance.json").decodeToString()
        for (value in listOf(source, reference) + images) assertTrue(proof.contains(hash(value)), folder)
        assertTrue(bytes(folder + "word-reference.pdf.provenance.json").decodeToString().contains(hash(source)))
        Loader.loadPDF(reference).use { word ->
            assertEquals(pageCount, word.numberOfPages)
            val expected = positions(word, pattern, tails)
            assertEquals(markerCount, expected.size)
            val wordImages = (0 until pageCount).map { PDFRenderer(word).renderImageWithDPI(it, 96f) }
            for (profile in profiles) Loader.loadPDF(generate(source, profile)).use { actual ->
                assertEquals(pageCount, actual.numberOfPages)
                assertEquals(text(word), text(actual))
                for (page in 0 until pageCount) {
                    assertEquals(word.getPage(page).mediaBox.width, actual.getPage(page).mediaBox.width, .1f)
                    assertEquals(word.getPage(page).mediaBox.height, actual.getPage(page).mediaBox.height, .1f)
                    assertTrue(actual.getPage(page).resources.fontNames.all { actual.getPage(page).resources.getFont(it).isEmbedded })
                }
                val found = positions(actual, pattern, tails)
                assertEquals(expected.keys, found.keys)
                val actualImages = (0 until pageCount).map { PDFRenderer(actual).renderImageWithDPI(it, 96f) }
                for ((marker, before) in expected) {
                    val after = found.getValue(marker)
                    assertEquals(before.x, after.x, .21f, marker)
                    assertEquals(before.y, after.y, .21f, marker)
                    if (colors) {

                        fun colorAt(image: java.awt.image.BufferedImage, position: Position) = image.getRGB(
                            kotlin.math.round((position.x + 40) * 96 / 72).toInt(), kotlin.math.round((position.y - 1) * 96 / 72).toInt())

                        assertEquals(colorAt(wordImages[before.page - 1], before), colorAt(actualImages[after.page - 1], after), marker)
                    }
                }
                for (page in 0 until pageCount) assertImagesMatch(ImageIO.read(images[page].inputStream()), actualImages[page]) {
                    pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                }
            }
        }
    }

}
