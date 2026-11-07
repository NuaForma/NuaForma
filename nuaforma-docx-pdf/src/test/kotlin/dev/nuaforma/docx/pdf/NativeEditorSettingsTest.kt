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

class NativeEditorSettingsTest {
    @TempDir lateinit var temporary: Path
    private val word = PdfFixture.W
    private val office = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private val defaults = """<w:hdrShapeDefaults xmlns:o="urn:schemas-microsoft-com:office:office" xmlns:v="urn:schemas-microsoft-com:vml"><o:shapedefaults v:ext="edit" spidmax="2050"/><o:shapelayout v:ext="edit"><o:idmap v:ext="edit" data="1,2"/></o:shapelayout></w:hdrShapeDefaults>"""

    private fun frame(band: String, y: Int) = """<w:p><w:r><w:pict xmlns:v="urn:schemas-microsoft-com:vml"><v:rect id="${band}Box" style="position:absolute;margin-left:300pt;margin-top:${y}pt;width:110pt;height:24pt;mso-position-horizontal-relative:page;mso-position-vertical-relative:page;v-text-anchor:top" filled="f" stroked="f"><v:textbox inset="0pt,0pt,0pt,0pt"><w:txbxContent><w:p><w:r><w:t xml:space="preserve">${band.uppercase(java.util.Locale.ROOT)} BOX </w:t></w:r><w:fldSimple w:instr="PAGE"><w:r><w:t>1</w:t></w:r></w:fldSimple></w:p></w:txbxContent></v:textbox></v:rect></w:pict></w:r></w:p>"""

    private fun source(letter: Boolean, metadata: Boolean): ByteArray {
        val parts = PdfFixture.parts()
        val height = if (letter) 792 else 842
        parts["word/styles.xml"] = parts.getValue("word/styles.xml").decodeToString().replace("DejaVu Sans", "Liberation Sans").toByteArray()
        if (metadata) parts["word/styles.xml"] = parts.getValue("word/styles.xml").decodeToString().replace("</w:style>", "<w:next w:val=\"DeliberatelyMissing\"/></w:style>").toByteArray()
        parts["word/document.xml"] = """<w:document xmlns:w="$word" xmlns:r="$office"><w:body><w:p><w:r><w:t>Editor settings report</w:t></w:r></w:p><w:p><w:pPr><w:pageBreakBefore/></w:pPr><w:r><w:t>Second page retained</w:t></w:r></w:p><w:sectPr><w:headerReference w:type="default" r:id="header"/><w:footerReference w:type="default" r:id="footer"/><w:pgSz w:w="${if (letter) 12240 else 11906}" w:h="${if (letter) 15840 else 16838}"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="360" w:footer="360" w:gutter="0"/></w:sectPr></w:body></w:document>""".toByteArray()
        parts["word/header1.xml"] = """<w:hdr xmlns:w="$word"><w:p><w:r><w:t>Header normal</w:t></w:r></w:p>${frame("header", 20)}</w:hdr>""".toByteArray()
        parts["word/footer1.xml"] = """<w:ftr xmlns:w="$word"><w:p><w:r><w:t>Footer normal</w:t></w:r></w:p>${frame("footer", height - 52)}</w:ftr>""".toByteArray()
        val editor = """<w:removePersonalInformation/><w:removeDateAndTime/><w:drawingGridHorizontalSpacing w:val="100"/><w:drawingGridVerticalSpacing w:val="284"/><w:drawingGridHorizontalOrigin w:val="1134"/><w:drawingGridVerticalOrigin w:val="1134"/><w:displayHorizontalDrawingGridEvery w:val="0"/><w:displayVerticalDrawingGridEvery w:val="2"/><w:doNotUseMarginsForDrawingGridOrigin/>"""
        parts["word/settings.xml"] = """<w:settings xmlns:w="$word"><w:defaultTabStop w:val="720"/>${if (metadata) defaults + editor + """<w:stylePaneSortMethod w:val="0004"/>""" else ""}</w:settings>""".toByteArray()
        parts["word/webSettings.xml"] = """<w:webSettings xmlns:w="$word">${if (metadata) """<w:relyOnVML/><w:targetScreenSz w:val="800x600"/>""" else ""}</w:webSettings>""".toByteArray()
        val relationKinds = listOf("header", "footer", "settings", "webSettings")
        val relations = relationKinds.joinToString("") { kind ->
            """<Relationship Id="$kind" Type="$office/$kind" Target="$kind${if (kind in setOf("header", "footer")) "1" else ""}.xml"/>"""
        }
        parts["word/_rels/document.xml.rels"] = parts.getValue("word/_rels/document.xml.rels").decodeToString().replace("</Relationships>", "$relations</Relationships>").toByteArray()
        val types = relationKinds.joinToString("") { kind ->
            """<Override PartName="/word/$kind${if (kind in setOf("header", "footer")) "1" else ""}.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.$kind+xml"/>"""
        }
        parts["[Content_Types].xml"] = parts.getValue("[Content_Types].xml").decodeToString().replace("</Types>", "$types</Types>").toByteArray()
        return PdfFixture.zip(parts)
    }

    private fun provider(): DocxPdfProvider {
        val font = requireNotNull(javaClass.getResourceAsStream("/own-engine-fonts/LiberationSans-Regular.ttf")).use { it.readBytes() }
        val validator = PdfProcessValidator(Path.of(System.getProperty("nuaforma.pdf.validation.java")),
            Files.list(Path.of(System.getProperty("nuaforma.pdf.validation"))).use { paths -> paths.filter { it.toString().endsWith(".jar") }.sorted().toList() }, temporary)
        return DocxPdfProvider(NativePdfConfiguration(listOf(NativePdfFont("Liberation Sans", "regular", font)), validator))
    }

    private fun pdf(provider: DocxPdfProvider, source: ByteArray, profile: String): ByteArray = ByteArrayOutputStream().also { output ->
        provider.generate(GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
            FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile)))), output, GenerationContext())
    }.toByteArray()

    @Test
    fun `identifier defaults and web preferences preserve VML frames page fields and every pixel in all PDF profiles`() {
        val provider = provider()
        val directory = Files.createDirectories(Path.of("build/227-editor-settings"))
        for (letter in listOf(false, true)) {
            val size = if (letter) "letter" else "a4"
            val baseline = source(letter, false)
            val input = source(letter, true)
            Files.write(directory.resolve("$size.docx"), input)
            val cleaned = ByteArrayOutputStream()
            val report = provider.cleanTemplate(input.inputStream(), cleaned, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
            assertTrue(report.changes.isEmpty(), report.changes.toString())
            assertArrayEquals(input, cleaned.toByteArray())
            Loader.loadPDF(pdf(provider, baseline, "standard")).use { reference ->
                for (profile in listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b")) {
                    val bytes = pdf(provider, input, profile)
                    Files.write(directory.resolve("$size-$profile.pdf"), bytes)
                    Loader.loadPDF(bytes).use { actual ->
                        assertEquals(2, actual.numberOfPages)
                        assertEquals(if (letter) 612f else 595.3f, actual.getPage(0).mediaBox.width, .1f)
                        assertEquals(PDFTextStripper().getText(reference), PDFTextStripper().getText(actual))
                        for (page in 0..1) {
                            val text = PDFTextStripper().apply { startPage = page + 1; endPage = page + 1 }.getText(actual)
                            assertTrue(text.contains("HEADER BOX ${page + 1}") && text.contains("FOOTER BOX ${page + 1}"))
                            val raster = PDFRenderer(actual).renderImageWithDPI(page, 96f)
                            assertImagesMatch(PDFRenderer(reference).renderImageWithDPI(page, 96f), raster) {
                                pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                            }
                            if (profile == "standard") ImageIO.write(raster, "png", directory.resolve("$size-page-${page + 1}.png").toFile())
                        }
                    }
                }
            }
            val docx = ByteArrayOutputStream()
            provider.generate(GenerationRequest(input.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.DOCX))), docx, GenerationContext())
            val parts = PdfFixture.unzip(docx.toByteArray())
            assertTrue(parts.getValue("word/settings.xml").decodeToString().contains("hdrShapeDefaults"))
            assertTrue(parts.getValue("word/webSettings.xml").decodeToString().contains("relyOnVML"))
            assertTrue(parts.getValue("word/header1.xml").decodeToString().contains("HEADER BOX"))
            assertTrue(parts.getValue("word/footer1.xml").decodeToString().contains("FOOTER BOX"))
        }
        assertEquals(0L, Files.list(temporary).use { it.count() })
    }
}
