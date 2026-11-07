package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.adapters.nativeengine.NativeDocxReader
import dev.nuaforma.pdf.*
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

class NativeJpegCompatibilityTest {
    @TempDir lateinit var temporary: Path
    private val w = PdfFixture.W
    private val rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private fun picture(id: Int, width: Int, height: Int, crop: Boolean = false) = """<w:r><w:drawing xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture" xmlns:r="$rel"><wp:inline><wp:extent cx="${width * 12700}" cy="${height * 12700}"/><wp:docPr id="$id" name="Synthetic picture $id"/><a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic><pic:nvPicPr><pic:cNvPr id="$id" name="Synthetic picture"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed="logo"/>${if (crop) """<a:srcRect l="10000" r="10000"/>""" else ""}<a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="${width * 12700}" cy="${height * 12700}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>"""

    private fun jpeg(): ByteArray {
        val image = BufferedImage(160, 80, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = java.awt.Color(0, 139, 144); graphics.fillRect(0, 0, 160, 80)
            graphics.color = java.awt.Color(37, 57, 84); graphics.fillRect(20, 20, 120, 40)
            return ByteArrayOutputStream().also { assertTrue(ImageIO.write(image, "jpeg", it)) }.toByteArray()
        } finally { graphics.dispose(); image.flush() }
    }

    private fun source(png: Boolean = false, label: Boolean = true, letter: Boolean = false): ByteArray {
        val parts = PdfFixture.parts()
        parts["word/styles.xml"] = parts.getValue("word/styles.xml").decodeToString().replace("DejaVu Sans", "Liberation Sans").toByteArray()
        val format = if (png) "png" else "jpeg"
        val jpg = jpeg()
        parts["word/media/logo.$format"] = if (png) ByteArrayOutputStream().also {
            ImageIO.write(ImageIO.read(jpg.inputStream()), "png", it)
        }.toByteArray() else jpg
        val imageLink = """<Relationship Id="logo" Type="$rel/image" Target="media/logo.$format"/>"""
        val relationRoot = "http://schemas.openxmlformats.org/package/2006/relationships"
        for (band in listOf("header", "footer")) {
            parts["word/${band}1.xml"] = """<w:${if (band == "header") "hdr" else "ftr"} xmlns:w="$w"><w:p><w:r><w:t>Existing $band marking</w:t></w:r></w:p><w:p>${picture(if (band == "header") 2 else 3, if (band == "header") 96 else 48, if (band == "header") 48 else 24)}</w:p></w:${if (band == "header") "hdr" else "ftr"}>""".toByteArray()
            parts["word/_rels/${band}1.xml.rels"] = """<Relationships xmlns="$relationRoot">$imageLink</Relationships>""".toByteArray()
        }
        parts["word/_rels/document.xml.rels"] = parts.getValue("word/_rels/document.xml.rels").decodeToString().replace("</Relationships>",
            """$imageLink<Relationship Id="header" Type="$rel/header" Target="header1.xml"/><Relationship Id="footer" Type="$rel/footer" Target="footer1.xml"/></Relationships>""").toByteArray()
        parts["word/document.xml"] = """<w:document xmlns:w="$w" xmlns:r="$rel"><w:body><w:p><w:r><w:t>JPEG trial {name}</w:t></w:r></w:p><w:p>${picture(1, 144, 72, true)}</w:p><w:p><w:pPr><w:pageBreakBefore/></w:pPr><w:r><w:t>Second page</w:t></w:r></w:p><w:sectPr><w:headerReference w:type="default" r:id="header"/><w:footerReference w:type="default" r:id="footer"/><w:pgSz w:w="${if (letter) 12240 else 11906}" w:h="${if (letter) 15840 else 16838}"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="360" w:footer="360" w:gutter="0"/></w:sectPr></w:body></w:document>""".toByteArray()
        var types = parts.getValue("[Content_Types].xml").decodeToString()
        types = types.replace("</Types>", """<Default Extension="$format" ContentType="image/${if (png) "png" else "jpeg"}"/><Override PartName="/word/header1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/><Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/></Types>""")
        if (label) {
            parts["docMetadata/LabelInfo.xml"] = """<labelList xmlns="http://schemas.microsoft.com/office/2020/mipLabelMetadata"><label id="{11111111-1111-1111-1111-111111111111}" siteId="{22222222-2222-2222-2222-222222222222}" enabled="1" removed="0" method="Standard" contentBits="2"/></labelList>""".toByteArray()
            parts["_rels/.rels"] = parts.getValue("_rels/.rels").decodeToString().replace("</Relationships>", """<Relationship Id="label" Type="http://schemas.microsoft.com/office/2020/02/relationships/classificationlabels" Target="docMetadata/LabelInfo.xml"/></Relationships>""").toByteArray()
            types = types.replace("</Types>", """<Override PartName="/docMetadata/LabelInfo.xml" ContentType="application/vnd.ms-office.classificationlabels+xml"/></Types>""")
        }
        parts["[Content_Types].xml"] = types.toByteArray()
        return PdfFixture.zip(parts)
    }

    private fun provider(): DocxPdfProvider {
        val fonts = listOf("regular" to "Regular", "bold" to "Bold").map { (face, file) ->
            NativePdfFont("Liberation Sans", face, requireNotNull(javaClass.getResourceAsStream("/own-engine-fonts/LiberationSans-$file.ttf")).use { it.readBytes() })
        }
        val validator = PdfProcessValidator(Path.of(System.getProperty("nuaforma.pdf.validation.java")),
            Files.list(Path.of(System.getProperty("nuaforma.pdf.validation"))).use { paths -> paths.filter { it.toString().endsWith(".jar") }.sorted().toList() }, temporary)
        return DocxPdfProvider(NativePdfConfiguration(fonts, validator))
    }

    private fun generate(source: ByteArray, profile: String): ByteArray = ByteArrayOutputStream().also { output ->
        provider().generate(GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
            FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile))), mapOf("name" to "NuaForma")), output, GenerationContext())
    }.toByteArray()

    @Test
    fun `JPEG body crop header footer and label metadata retain PNG placement in all four validated profiles`() {
        val directory = Files.createDirectories(Path.of("build/226-jpeg"))
        for (letter in listOf(false, true)) {
            val input = source(letter = letter)
            Files.write(directory.resolve("${if (letter) "letter" else "a4"}.docx"), input)
            val cleaned = ByteArrayOutputStream()
            val cleanup = provider().cleanTemplate(input.inputStream(), cleaned, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
            assertTrue(cleanup.changes.isEmpty())
            assertArrayEquals(input, cleaned.toByteArray(), "Supported template cleanup must preserve JPEG, labels and unevaluated markers")
            val model = NativeDocxReader(GenerationContext()) { _, _, _ -> "regular" }.read(input)
            val image = model.blocks.filterIsInstance<PdfParagraph>().flatMap { it.runs }.filterIsInstance<PdfImageRun>().single().image
            assertEquals(144f, image.width)
            assertEquals(72f, image.height)
            assertEquals(.1f, image.crop.left)
            val expected = generate(source(png = true, label = false, letter = letter), "standard")
            Loader.loadPDF(expected).use { reference ->
                for (profile in listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b")) {
                    val bytes = generate(input, profile)
                    Files.write(directory.resolve("${if (letter) "letter" else "a4"}-$profile.pdf"), bytes)
                    Loader.loadPDF(bytes).use { pdf ->
                        assertEquals(2, pdf.numberOfPages)
                        assertEquals(if (letter) 612f else 595.3f, pdf.getPage(0).mediaBox.width, .1f)
                        val text = PDFTextStripper().getText(pdf)
                        assertTrue(text.contains("JPEG trial NuaForma") && text.contains("Second page"))
                        assertEquals(2, Regex("Existing header marking").findAll(text).count())
                        assertEquals(2, Regex("Existing footer marking").findAll(text).count())
                        for (page in 0..1) {
                            assertEquals(if (page == 0) 3 else 2, pdf.getPage(page).resources.xObjectNames.count())
                            val raster = PDFRenderer(pdf).renderImageWithDPI(page, 96f)
                            assertImagesMatch(PDFRenderer(reference).renderImageWithDPI(page, 96f), raster)
                            if (profile == "standard") ImageIO.write(raster, "png", directory.resolve("${if (letter) "letter" else "a4"}-page-${page + 1}.png").toFile())
                        }
                    }
                }
            }
            val output = ByteArrayOutputStream()
            provider().generate(GenerationRequest(input.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.DOCX)), mapOf("name" to "NuaForma")), output, GenerationContext())
            val actual = PdfFixture.unzip(output.toByteArray())
            val original = PdfFixture.unzip(input)
            assertArrayEquals(original["word/media/logo.jpeg"], actual["word/media/logo.jpeg"])
            val xml = dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml(GenerationContext())
            val before = xml.read(original.getValue("docMetadata/LabelInfo.xml")).descendants().map { it.name to it.attributes.toMap() }.toList()
            val after = xml.read(actual.getValue("docMetadata/LabelInfo.xml")).descendants().map { it.name to it.attributes.toMap() }.toList()
            assertEquals(before, after, "Existing DOCX serialization may add an XML declaration, but retains every label value")
        }
        assertEquals(0L, Files.list(temporary).use { it.count() })
    }

    @Test
    fun `cleanup reports a malformed JPEG media part and removes all its dangling drawings`() {
        val parts = PdfFixture.unzip(source()).toMutableMap()
        parts["word/media/logo.jpeg"] = byteArrayOf(1, 2, 3, 4)
        val output = ByteArrayOutputStream()
        val report = provider().cleanTemplate(PdfFixture.zip(parts).inputStream(), output, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
        assertTrue(report.changes.any { it.action == TemplateCleanupAction.REMOVE_PART && it.part == "word/media/logo.jpeg" && it.reason.contains("JPEG") })
        val cleaned = PdfFixture.unzip(output.toByteArray())
        assertFalse("word/media/logo.jpeg" in cleaned)
        assertTrue("docMetadata/LabelInfo.xml" in cleaned)
        assertTrue(cleaned.getValue("word/document.xml").decodeToString().contains("{name}"))
        NativeDocxReader(GenerationContext()) { _, _, _ -> "regular" }.read(output.toByteArray())
    }

}
