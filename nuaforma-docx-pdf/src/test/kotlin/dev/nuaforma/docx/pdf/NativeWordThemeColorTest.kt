package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.ownengine.WordLayoutOracle
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import dev.nuaforma.pdf.PdfProcessValidator
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class NativeWordThemeColorTest {
    @TempDir lateinit var temporary: Path

    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes() }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun provider(): DocxPdfProvider {
        val fonts = listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic", "boldItalic" to "BoldItalic").map { (face, name) ->
            NativePdfFont("Liberation Sans", face, resource("/own-engine-fonts/LiberationSans-$name.ttf"))
        }
        return DocxPdfProvider(NativePdfConfiguration(fonts, PdfProcessValidator(
            Path.of(System.getProperty("nuaforma.pdf.validation.java")),
            Files.list(Path.of(System.getProperty("nuaforma.pdf.validation"))).use { paths -> paths.filter { it.toString().endsWith(".jar") }.sorted().toList() }, temporary)))
    }

    private fun request(source: ByteArray, profile: String = "standard") = GenerationRequest(source.inputStream(),
        GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile))))

    private fun text(pdf: PDDocument, page: Int) = PDFTextStripper().also { it.startPage = page; it.endPage = page }
        .getText(pdf).replace(Regex("\\s+"), " ").trim()

    @Test
    fun `theme palettes styles and direct overrides match Word in every PDF profile`() {
        val provider = provider()
        val output = Files.createDirectories(Path.of("build/245-theme-colors"))
        val marker = Regex("([KST][0-9]{2})\\b")
        val transformed = setOf("K12", "K13", "K18", "K19", "K24", "K25", "K30", "K31", "K32", "S02")
        for (paper in listOf("a4", "letter")) {
            val base = "/theme-colors/$paper/"
            val source = resource(base + "source.docx")
            val reference = resource(base + "word-reference.pdf")
            val receipt = resource(base + "word-reference.pdf.provenance.json").decodeToString()
            assertTrue(receipt.contains(hash(source)) && receipt.contains(hash(reference)))
            val cleaned = ByteArrayOutputStream()
            assertTrue(provider.cleanTemplate(source.inputStream(), cleaned, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))).changes.isEmpty())
            assertArrayEquals(source, cleaned.toByteArray())
            Loader.loadPDF(reference).use { word ->
                val expected = WordLayoutOracle.positions(word, marker)
                assertTrue(expected.size >= 60, paper)
                for (profile in listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b")) {
                    val rendered = ByteArrayOutputStream()
                    provider.generate(request(source, profile), rendered, GenerationContext())
                    Files.write(output.resolve("$paper-$profile.pdf"), rendered.toByteArray())
                    Loader.loadPDF(rendered.toByteArray()).use { actual ->
                        assertEquals(word.numberOfPages, actual.numberOfPages, "$paper $profile")
                        val positions = WordLayoutOracle.positions(actual, marker)
                        assertEquals(expected.keys, positions.keys)
                        for ((name, location) in expected) {
                            val found = positions.getValue(name)
                            assertEquals(location.page, found.page, "$paper $name page")
                            assertEquals(location.x, found.x, .3f, "$paper $name x")
                            assertEquals(location.y, found.y, 1.5f, "$paper $name baseline")
                            assertEquals(location.face, found.face, "$paper $name face")
                            assertEquals(location.size, found.size, .05f, "$paper $name size")
                            val tolerance = if (name in transformed || name.startsWith("T")) 1 else 0
                            for (shift in listOf(0, 8, 16)) assertTrue(kotlin.math.abs((location.rgb shr shift and 255) -
                                (found.rgb shr shift and 255)) <= tolerance, "$paper $name RGB channel $shift")
                        }
                        assertEquals(0x125634, positions.getValue("S03").rgb)
                        WordLayoutOracle.verifyTableFills(source, word, actual, page = 1, topOffset = 15f)
                        val provenance = resource(base + "golden-provenance.json").decodeToString()
                        assertTrue(provenance.contains(hash(source)) && provenance.contains(hash(reference)))
                        for (page in 0 until actual.numberOfPages) {
                            assertEquals(text(word, page + 1), text(actual, page + 1), "$paper $profile page $page text")
                            assertEquals(word.getPage(page).mediaBox.width, actual.getPage(page).mediaBox.width, .1f)
                            assertEquals(word.getPage(page).mediaBox.height, actual.getPage(page).mediaBox.height, .1f)
                            assertTrue(actual.getPage(page).resources.fontNames.all { actual.getPage(page).resources.getFont(it).isEmbedded })
                            val golden = resource(base + "page-${page + 1}.png")
                            assertTrue(provenance.contains(hash(golden)))
                            val raster = PDFRenderer(actual).renderImageWithDPI(page, 96f)
                            assertImagesMatch(ImageIO.read(golden.inputStream()), raster) {
                                pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                            }
                            ImageIO.write(raster, "png", output.resolve("$paper-$profile-page-${page + 1}.png").toFile())
                        }
                    }
                }
            }
        }
    }

    private fun rewrite(source: ByteArray, part: String, change: (String) -> String): ByteArray {
        val output = ByteArrayOutputStream()
        var changed = false
        ZipInputStream(source.inputStream()).use { input -> ZipOutputStream(output).use { zip ->
            while (true) {
                val entry = input.nextEntry ?: break
                val bytes = input.readBytes()
                zip.putNextEntry(ZipEntry(entry.name))
                if (entry.name == part) {
                    val before = bytes.decodeToString()
                    val after = change(before)
                    require(before != after)
                    zip.write(after.toByteArray()); changed = true
                } else zip.write(bytes)
                zip.closeEntry()
            }
        } }
        require(changed)
        return output.toByteArray()
    }

    @Test
    fun `malformed original colors and unresolved palettes reject without partial PDF`() {
        val provider = provider()
        val source = resource("/theme-colors/a4/source.docx")
        val cases = listOf(
            "word/document.xml" to { text: String -> text.replaceFirst("w:themeColor=\"text1\"", "w:themeColor=\"text1\" extra=\"1\"") },
            "word/document.xml" to { text: String -> text.replaceFirst("w:themeColor=\"text1\"", "w:themeColor=\"unknown\"") },
            "word/document.xml" to { text: String -> text.replaceFirst("<w:color w:val=\"FF00FF\" w:themeColor=\"text1\"/>",
                "<w:color w:val=\"FF00FF\" w:themeColor=\"text1\"/><w:color w:val=\"123456\"/>") },
            "word/theme/theme1.xml" to { text: String -> text.replaceFirst("</a:clrScheme>", "<a:dk1><a:srgbClr val=\"123456\"/></a:dk1></a:clrScheme>") },
            "word/theme/theme1.xml" to { text: String -> text.replaceFirst("<a:dk1>", "<a:unknown>").replaceFirst("</a:dk1>", "</a:unknown>") },
            "word/theme/theme1.xml" to { text: String -> text.replaceFirst("<a:srgbClr val=\"1F3147\"/>", "<a:srgbClr val=\"1F3147\"><a:alpha val=\"50000\"/></a:srgbClr>") })
        for ((part, change) in cases) {
            val output = ByteArrayOutputStream()
            val error = assertThrows(GenerationException::class.java) {
                provider.generate(request(rewrite(source, part, change)), output,
                    GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
            }
            assertEquals(0, output.size(), part)
            assertTrue(error.diagnostics.any { it.code == DiagnosticCodes.UNSUPPORTED_INPUT && it.part == part }, "$part ${error.diagnostics.map { it.part to it.message }}")
        }
    }
}
