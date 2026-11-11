package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.pdf.PdfProcessValidator
import dev.nuaforma.docx.pdf.adapters.nativeengine.WordMarkupCompatibility
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO

class NativeCompatibilityObjectsTest {
    @TempDir lateinit var temporary: Path

    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes() }

    private fun provider() = DocxPdfProvider(NativePdfConfiguration(listOf(
        NativePdfFont("Liberation Sans", "regular", resource("/own-engine-fonts/LiberationSans-Regular.ttf"))),
        PdfProcessValidator(Path.of(System.getProperty("nuaforma.pdf.validation.java")),
            Files.list(Path.of(System.getProperty("nuaforma.pdf.validation"))).use { files -> files.filter { it.toString().endsWith(".jar") }.sorted().toList() }, temporary)))

    private fun render(provider: DocxPdfProvider, source: ByteArray, profile: String, data: Map<String, Any?> = emptyMap()) = ByteArrayOutputStream().also { output ->
        provider.generate(GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
            FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile))), data), output, GenerationContext())
    }.toByteArray()

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun glyphs(bytes: ByteArray): List<Triple<String, Float, Float>> {
        val result = mutableListOf<Triple<String, Float, Float>>()
        Loader.loadPDF(bytes).use { pdf ->
            object : PDFTextStripper() {

                override fun processTextPosition(position: TextPosition) {
                    if (position.unicode.isNotBlank()) result += Triple(position.unicode, position.xDirAdj, position.yDirAdj)
                    super.processTextPosition(position)
                }

            }.getText(pdf)
        }
        return result
    }

    @Test
    fun `selected compatibility frames match real Word on A4 and Letter in all validated profiles`() {
        val provider = provider()
        val directory = Files.createDirectories(Path.of("build/242-compatibility-objects"))
        for (name in listOf("a4", "letter")) {
            val base = "/compatibility-objects/$name/"
            val source = resource(base + "source.docx")
            val reference = resource(base + "word-reference.pdf")
            val provenance = resource(base + "word-reference.pdf.provenance.json").decodeToString()
            assertTrue(provenance.contains(hash(source)) && provenance.contains(hash(reference)))
            val clean = ByteArrayOutputStream()
            val report = provider.cleanTemplate(source.inputStream(), clean, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
            assertTrue(report.changes.isEmpty(), report.changes.toString())
            assertArrayEquals(source, clean.toByteArray())
            val golden = resource(base + "page-1.png")
            val goldenProvenance = resource(base + "golden-provenance.json").decodeToString()
            assertTrue(listOf(source, reference, golden).all { goldenProvenance.contains(hash(it)) })
            val expected = glyphs(reference)
            val withoutSwitches = WordMarkupCompatibility.prepare(source, GenerationContext())
            Loader.loadPDF(render(provider, withoutSwitches, "standard")).use { baseline ->
                val unchanged = PDFRenderer(baseline).renderImageWithDPI(0, 96f)
                for (profile in listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b")) {
                    val bytes = render(provider, source, profile)
                    Files.write(directory.resolve("$name-$profile.pdf"), bytes)
                    val actual = glyphs(bytes)
                    assertEquals(expected.map { it.first }, actual.map { it.first })
                    for (index in expected.indices) {
                        assertEquals(expected[index].second, actual[index].second, .75f, "$name $profile glyph $index x")
                        assertEquals(expected[index].third, actual[index].third, .4f, "$name $profile glyph $index baseline")
                    }
                    Loader.loadPDF(bytes).use { pdf ->
                        assertEquals(1, pdf.numberOfPages)
                        assertEquals(if (name == "letter") 612f else 595.3f, pdf.getPage(0).mediaBox.width, .1f)
                        assertTrue(pdf.getPage(0).resources.fontNames.all { pdf.getPage(0).resources.getFont(it).isEmbedded })
                        val raster = PDFRenderer(pdf).renderImageWithDPI(0, 96f)
                        assertImagesMatch(unchanged, raster) {
                            pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                        }
                        assertImagesMatch(ImageIO.read(golden.inputStream()), raster) {
                            pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                        }
                        ImageIO.write(raster, "png", directory.resolve("$name-$profile.png").toFile())
                    }
                }
            }
        }
    }

    @Test
    fun `automatic alignment rejects an unqualified script explicitly without publishing a partial PDF`() {
        val source = resource("/compatibility-objects/a4/source.docx")
        val changed = PdfFixture.zip(PdfFixture.unzip(source).mapValues { (part, bytes) ->
            if (part == "word/document.xml") bytes.decodeToString().replace("Short final paragraph.", "Latin עברית 123").toByteArray() else bytes
        })
        val output = ByteArrayOutputStream()
        val error = assertThrows(GenerationException::class.java) {
            provider().generate(GenerationRequest(changed.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                FormatRequest(DocumentFormat.PDF, mapOf("profile" to "standard")))), output, GenerationContext())
        }
        assertEquals(0, output.size())
        assertTrue(error.diagnostics.any { it.rejectionReason == RejectionReason.NATIVE_DOCX_PROFILE_UNSUPPORTED })
    }


    private fun alterFrameText(choiceText: String, fallbackText: String): ByteArray {
        val parts = PdfFixture.unzip(resource("/compatibility-objects/a4/source.docx")).toMutableMap()
        val xml = ConversionXml(GenerationContext())
        val root = xml.read(parts.getValue("word/document.xml"))
        val mc = "http://schemas.openxmlformats.org/markup-compatibility/2006"
        for ((branch, text) in listOf("Choice" to choiceText, "Fallback" to fallbackText)) {
            val parent = root.descendants().first { it.name.namespaceURI == mc && it.name.localPart == branch }
            val target = parent.descendants().first { it.name.namespaceURI == PdfFixture.W && it.name.localPart == "t" }
            target.children.clear(); target.children += XmlText(text)
        }
        parts["word/document.xml"] = xml.write(root)
        return PdfFixture.zip(parts)
    }

    @Test
    fun `selected variables and blocks are evaluated once while hidden fallback syntax is ignored`() {
        val source = alterFrameText("{#if active}MC01 {name}{#else}Other{/if}", "HIDDEN {missing")
        val bytes = render(provider(), source, "standard", mapOf("active" to true, "name" to "Example"))
        Loader.loadPDF(bytes).use { pdf ->
            val text = PDFTextStripper().getText(pdf)
            assertEquals(1, Regex("MC01 Example").findAll(text).count())
            assertFalse(text.contains("HIDDEN") || text.contains("Other") || text.contains("{name}"))
        }
    }

    @Test
    fun `syntax diagnostics mark only the selected source representation`() {
        val source = alterFrameText("MC01 {broken", "HIDDEN {alsoBroken")
        val output = ByteArrayOutputStream()
        provider().diagnoseDocument(GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
            FormatRequest(DocumentFormat.PDF, mapOf("profile" to "standard")))), output,
            GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)), false)
        val document = PdfFixture.unzip(output.toByteArray()).getValue("word/document.xml").decodeToString()
        assertTrue(document.contains("[ERROR 1]"), document)
        assertFalse(document.contains("HIDDEN") || document.contains("AlternateContent"), document)
    }

    @Test
    fun `an unsupported selected effect does not silently fall back to a different shape`() {
        val source = resource("/compatibility-objects/a4/source.docx")
        val changed = PdfFixture.zip(PdfFixture.unzip(source).mapValues { (part, bytes) ->
            if (part == "word/document.xml") bytes.decodeToString().replace("<wps:spPr>", "<wps:spPr><a:effectLst/>").toByteArray() else bytes
        })
        val output = ByteArrayOutputStream()
        val error = assertThrows(GenerationException::class.java) {
            provider().generate(GenerationRequest(changed.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                FormatRequest(DocumentFormat.PDF, mapOf("profile" to "standard")))), output, GenerationContext())
        }
        assertEquals(0, output.size())
        assertTrue(error.diagnostics.any { it.rejectionReason == RejectionReason.NATIVE_DOCX_PROFILE_UNSUPPORTED })
    }


    @Test
    fun `page-band alternatives select one visible template stream`() {
        val parts = PdfFixture.unzip(resource("/compatibility-objects/a4/source.docx")).toMutableMap()
        val office = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        val mc = "http://schemas.openxmlformats.org/markup-compatibility/2006"
        parts["word/footer1.xml"] = """<w:ftr xmlns:w="${PdfFixture.W}" xmlns:mc="$mc"><w:p><w:r><mc:AlternateContent><mc:Choice Requires="w"><w:t>FOOTER01 {name}</w:t></mc:Choice><mc:Fallback><w:t>HIDDEN {oops</w:t></mc:Fallback></mc:AlternateContent></w:r></w:p></w:ftr>""".toByteArray()
        parts["word/document.xml"] = parts.getValue("word/document.xml").decodeToString()
            .replace("<w:sectPr>", """<w:sectPr><w:footerReference xmlns:r="$office" w:type="default" r:id="footerMC"/>""")
            .replace("<w:pgMar ", "<w:pgMar w:header=\"360\" w:footer=\"360\" ").toByteArray()
        parts["word/_rels/document.xml.rels"] = parts.getValue("word/_rels/document.xml.rels").decodeToString()
            .replace("</Relationships>", """<Relationship Id="footerMC" Type="$office/footer" Target="footer1.xml"/></Relationships>""").toByteArray()
        parts["[Content_Types].xml"] = parts.getValue("[Content_Types].xml").decodeToString()
            .replace("</Types>", """<Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/></Types>""").toByteArray()
        Loader.loadPDF(render(provider(), PdfFixture.zip(parts), "standard", mapOf("name" to "Example"))).use { pdf ->
            val text = PDFTextStripper().getText(pdf)
            assertEquals(1, Regex("FOOTER01 Example").findAll(text).count())
            assertFalse(text.contains("HIDDEN"))
        }
    }

}
