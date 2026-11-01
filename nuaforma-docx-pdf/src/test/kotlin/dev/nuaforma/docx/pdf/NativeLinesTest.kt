package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.adapters.nativeengine.NativeDocxReader
import dev.nuaforma.docx.pdf.adapters.nativeengine.NativeDocxReadException
import dev.nuaforma.pdf.*
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
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

class NativeLinesTest {
    @TempDir lateinit var temporary: Path
    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes() }
    private fun source() = resource("/own-engine-lines/source.docx")
    private fun fonts() = listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic", "boldItalic" to "BoldItalic").map { (id, file) ->
        PdfFont(id, resource("/own-engine-fonts/LiberationSans-$file.ttf"))
    }
    private fun read(bytes: ByteArray = source()) = NativeDocxReader(GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))) { _, bold, italic ->
        when { bold && italic -> "boldItalic"; bold -> "bold"; italic -> "italic"; else -> "regular" }
    }.read(bytes)
    private fun mutate(part: String = "word/document.xml", change: (String) -> String): ByteArray {
        val parts = PdfFixture.unzip(source()).toMutableMap()
        val old = parts.getValue(part).decodeToString()
        val changed = change(old); check(changed != old)
        parts[part] = changed.toByteArray()
        return PdfFixture.zip(parts)
    }

    @Test fun `reference source and golden images match their protected fingerprints`() {
        resource("/own-engine-lines/inputs.sha256").decodeToString().lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val (expected, name) = line.trim().split(Regex("\\s+"), limit = 2)
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(resource("/own-engine-lines/$name"))
            assertEquals(expected, java.util.HexFormat.of().formatHex(digest), "Protected reference $name changed; review rather than regenerate")
        }
    }

    @Test fun `borders and passive lines preserve source geometry and text in real PDF pages`() {
        val document = read()
        val paragraphs = document.blocks.filterIsInstance<PdfParagraph>()
        fun named(prefix: String) = paragraphs.single { p -> p.runs.filterIsInstance<PdfTextRun>().joinToString("") { it.text }.startsWith(prefix) }
        assertEquals(PdfLineStyle.DOUBLE, named("B02").borders!!.bottom!!.stroke.style)
        assertEquals(named("B01").borders, named("I01").borders)
        assertNull(named("I02").borders!!.bottom)
        assertEquals(10, paragraphs.sumOf { it.lines.size })
        assertEquals(1.5f, named("D01").lines.single().stroke.width)
        assertEquals(24f, named("D01").lines.single().fromX)
        assertEquals(PdfLineStyle.DOTTED, named("D06").lines.single().stroke.style)
        val directory = Files.createDirectories(Path.of("build/172-lines"))
        val bytes = PdfEngine().render(document, fonts())
        Files.write(directory.resolve("native.pdf"), bytes)
        Loader.loadPDF(bytes).use { pdf ->
            assertTrue(pdf.numberOfPages >= 3)
            val text = PDFTextStripper().getText(pdf)
            for (id in listOf("B01", "B02", "B03", "B04", "I01", "I02", "G01", "G02", "G11", "G12", "T01", "P26") + (1..10).map { "D%02d".format(it) })
                assertEquals(1, Regex("\\b$id\\b").findAll(text).count(), id)
            for (page in 0 until pdf.numberOfPages) {
                val raster = PDFRenderer(pdf).renderImageWithDPI(page, 96f)
                ImageIO.write(raster, "png", directory.resolve("page-${page + 1}.png").toFile())
                assertGolden(page, raster)
                assertTrue(PDFTextStripper().apply { startPage = page + 1; endPage = page + 1 }.getText(pdf).contains("HEADER"))
            }
        }
    }

    private fun assertGolden(page: Int, actual: java.awt.image.BufferedImage) {
        requireNotNull(javaClass.getResourceAsStream("/own-engine-lines/page-${page + 1}.png")).use {
            assertImagesMatch(ImageIO.read(it), actual, "Native line page ${page + 1}") {
                pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
            }
        }
    }

    @Test fun `drawn lines inside cells retain their local origin`() {
        val line = """<w:r><w:pict><v:line id="cellLine" style="position:absolute;mso-position-horizontal-relative:text;mso-position-vertical-relative:text" from="220pt,8pt" to="260pt,8pt" strokeweight="1pt" strokecolor="#008B90"/></w:pict></w:r>"""
        val input = mutate {
            it.replace("T01 Border inside a table cell</w:t></w:r>", "T01 Border inside a table cell</w:t></w:r>$line")
                .replace("</w:tcPr>", "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"FFF4CC\"/></w:tcPr>")
        }
        val document = read(input)
        val cell = document.blocks.filterIsInstance<PdfTable>().single().rows.single().cells.single().blocks.single() as PdfParagraph
        assertEquals(220f, cell.lines.single().fromX); assertFalse(cell.lines.single().pageX)
        val directory = Files.createDirectories(Path.of("build/172-lines"))
        Files.write(directory.resolve("cell-line.docx"), input)
        val bytes = PdfEngine().render(document, fonts())
        Files.write(directory.resolve("cell-line.pdf"), bytes)
        Loader.loadPDF(bytes).use { pdf ->
            val image = PDFRenderer(pdf).renderImageWithDPI(2, 144f)
            assertEquals(0x008B90, image.getRGB(200, 621) and 0xFFFFFF, "The cell fill must not cover its paragraph border")
        }
    }

    @Test fun `connected frame spans additional leading paragraph space`() {
        val input = mutate { xml ->
            val text = xml.indexOf("G12 Connected frame")
            val position = xml.lastIndexOf("<w:pPr>", text) + "<w:pPr>".length
            xml.substring(0, position) + "<w:spacing w:before=\"400\"/>" + xml.substring(position)
        }
        val directory = Files.createDirectories(Path.of("build/172-lines"))
        Files.write(directory.resolve("frame-spacing.docx"), input)
        Files.write(directory.resolve("frame-spacing.pdf"), PdfEngine().render(read(input), fonts()))
    }

    @Test fun `unsupported border effects and unsafe line geometry identify the actual construct`() {
        val variants = listOf<(String) -> String>(
            { it.replaceFirst("w:val=\"single\" w:sz=\"8\"", "w:val=\"wave\" w:sz=\"8\"") },
            { it.replaceFirst("w:space=\"4\"", "w:space=\"4\" w:shadow=\"1\"") },
            { it.replaceFirst("strokeweight=\"1.5pt\"", "strokeweight=\"20pt\"") },
            { it.replaceFirst("dashstyle=\"dash\"", "dashstyle=\"longDashDotDot\"") },
            { it.replaceFirst("<a:prstDash val=\"solid\"/>", "<a:headEnd type=\"triangle\"/>") },
            { it.replaceFirst("<a:srgbClr val=\"008B90\"/>", "<a:srgbClr val=\"008B90\"><a:alpha val=\"50000\"/></a:srgbClr>") },
            { it.replaceFirst("<wp:wrapNone/>", "<wp:wrapSquare wrapText=\"bothSides\"/>") },
            { it.replaceFirst("relativeFrom=\"column\"", "relativeFrom=\"character\"") },
            { it.replaceFirst("<wp:posOffset>304800</wp:posOffset>", "<wp:align>center</wp:align>") },
            { it.replaceFirst("rot=\"0\"", "rot=\"invalid\"") },
            { it.replaceFirst("flipH=\"false\"", "flipH=\"invalid\"") },
            { it.replaceFirst("<a:ext cx=\"2540000\" cy=\"0\"/>", "<a:ext cx=\"2540001\" cy=\"0\"/>") },
            { it.replaceFirst("<a:off x=\"0\" y=\"0\"/>", "<a:off x=\"12700\" y=\"0\"/>") },
            { it.replaceFirst("effectExtent l=\"0\"", "effectExtent l=\"10\"") },
            { it.replaceFirst("cmpd=\"sng\"", "cmpd=\"thickThin\"") },
            { it.replaceFirst("layoutInCell=\"1\"", "layoutInCell=\"0\"") },
            { it.replaceFirst("simplePos=\"0\"", "simplePos=\"1\"") },
            { it.replaceFirst("behindDoc=\"1\"", "behindDoc=\"invalid\"") },
        )
        for (variant in variants) {
            val error = assertThrows(NativeDocxReadException::class.java) { read(mutate(change = variant)) }
            assertEquals("word/document.xml", error.part)
            assertNotNull(error.sourceContext.path)
        }
    }

    @Test fun `registered provider supports every output profile with line artwork`() {
        val validator = PdfProcessValidator(Path.of(System.getProperty("nuaforma.pdf.validation.java")),
            Files.list(Path.of(System.getProperty("nuaforma.pdf.validation"))).use { paths -> paths.filter { it.toString().endsWith(".jar") }.sorted().toList() }, temporary)
        val provider = DocxPdfProvider(NativePdfConfiguration(fonts().mapIndexed { index, font ->
            NativePdfFont("Liberation Sans", listOf("regular", "bold", "italic", "boldItalic")[index], font.bytes())
        }, validator))
        for (profile in PdfOutputProfile.entries) {
            val output = ByteArrayOutputStream()
            try {
                provider.generate(GenerationRequest(source().inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                    FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile.id)))), output, GenerationContext())
            } catch (error: GenerationException) {
                throw AssertionError(error.diagnostics.joinToString("\n") { "${it.code}: ${it.message} ${it.rejectionReason} ${it.part} ${it.location}" }, error)
            }
            Loader.loadPDF(output.toByteArray()).use { pdf ->
                assertEquals(4, pdf.numberOfPages)
                for (page in 0 until pdf.numberOfPages) assertGolden(page, PDFRenderer(pdf).renderImageWithDPI(page, 96f))
            }
        }
    }
}
