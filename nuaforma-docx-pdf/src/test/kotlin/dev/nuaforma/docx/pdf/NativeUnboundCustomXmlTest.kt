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
import java.security.MessageDigest
import javax.imageio.ImageIO
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NativeUnboundCustomXmlTest {
    @TempDir lateinit var temporary: Path

    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes() }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun provider() = DocxPdfProvider(NativePdfConfiguration(listOf(NativePdfFont("Liberation Sans", "regular",
        resource("/own-engine-fonts/LiberationSans-Regular.ttf"))), PdfProcessValidator(
        Path.of(System.getProperty("nuaforma.pdf.validation.java")),
        Files.list(Path.of(System.getProperty("nuaforma.pdf.validation"))).use { paths -> paths.filter { it.toString().endsWith(".jar") }.sorted().toList() }, temporary)))

    private fun request(source: ByteArray, profile: String = "standard") = GenerationRequest(source.inputStream(),
        GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile))))

    @Test
    fun `unbound data preserves source bytes Word pages and existing profile goldens`() {
        val provider = provider()
        val output = Files.createDirectories(Path.of("build/250-unbound-custom-xml"))
        for ((name, baseline) in listOf("a4" to "trailing-1", "letter" to "bands-letter")) {
            val base = "/unbound-custom-xml/$name/"
            val source = resource(base + "source.docx")
            val reference = resource(base + "word-reference.pdf")
            val receipt = resource(base + "word-reference.pdf.provenance.json").decodeToString()
            val goldenReceipt = resource(base + "golden-provenance.json").decodeToString()
            assertTrue(receipt.contains(hash(source)) && receipt.contains(hash(reference)))
            assertTrue(goldenReceipt.contains(hash(source)) && goldenReceipt.contains(hash(reference)))
            val legacy = resource("/passive-office-package/$name/source.docx")
            val oldParts = PdfFixture.unzip(legacy)
            val newParts = PdfFixture.unzip(source)
            assertEquals(oldParts.size + 6, newParts.size)
            oldParts.filterKeys { it !in setOf("word/_rels/document.xml.rels", "[Content_Types].xml") }.forEach { (part, bytes) -> assertArrayEquals(bytes, newParts[part], part) }
            assertTrue(goldenReceipt.contains(hash(legacy)))
            val cleaned = ByteArrayOutputStream()
            assertTrue(provider.cleanTemplate(source.inputStream(), cleaned,
                GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))).changes.isEmpty())
            assertArrayEquals(source, cleaned.toByteArray())
            Loader.loadPDF(reference).use { word ->
                Loader.loadPDF(resource("/passive-office-package/$name/word-reference.pdf")).use { oldWord ->
                    assertEquals(1, word.numberOfPages)
                    assertImagesMatch(PDFRenderer(oldWord).renderImageWithDPI(0, 96f), PDFRenderer(word).renderImageWithDPI(0, 96f)) {
                        pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                    }
                }
                for (profile in listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b")) {
                    val rendered = ByteArrayOutputStream()
                    provider.generate(request(source, profile), rendered, GenerationContext())
                    Files.write(output.resolve("$name-$profile.pdf"), rendered.toByteArray())
                    Loader.loadPDF(rendered.toByteArray()).use { actual ->
                        assertEquals(1, actual.numberOfPages)
                        assertEquals(PDFTextStripper().getText(word).replace(Regex("\\s+"), " ").trim(),
                            PDFTextStripper().getText(actual).replace(Regex("\\s+"), " ").trim())
                        assertEquals(word.getPage(0).mediaBox.width, actual.getPage(0).mediaBox.width, .1f)
                        assertEquals(word.getPage(0).mediaBox.height, actual.getPage(0).mediaBox.height, .1f)
                        assertTrue(actual.getPage(0).resources.fontNames.all { actual.getPage(0).resources.getFont(it).isEmbedded })
                        val golden = resource("/typography-preferences/$baseline/page-1.png")
                        assertTrue(goldenReceipt.contains(hash(golden)))
                        assertImagesMatch(ImageIO.read(golden.inputStream()), PDFRenderer(actual).renderImageWithDPI(0, 96f)) {
                            pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                        }
                    }
                }
            }
        }
    }

    private fun changed(source: ByteArray, name: String, transform: (String) -> String): ByteArray {
        val parts = PdfFixture.unzip(source)
        val before = parts.getValue(name).decodeToString()
        val after = transform(before)
        require(before != after)
        return PdfFixture.zip(parts.mapValues { (part, bytes) -> if (part == name) after.toByteArray() else bytes })
    }

    @Test
    fun `active bound malformed and ambiguously owned data cannot produce partial output`() {
        val provider = provider()
        val source = resource("/unbound-custom-xml/a4/source.docx")
        val negative = listOf(
            changed(source, "word/document.xml") { it.replace("<w:body>", "<w:body><w:sdt><w:sdtPr><w:dataBinding w:xpath=\"/data\" w:storeItemID=\"{00000000-0000-0000-0000-000000000090}\"/></w:sdtPr><w:sdtContent><w:p/></w:sdtContent></w:sdt>") },
            changed(source, "customXml/_rels/item90.xml.rels") { it.replace("Target=\"itemProps90.xml\"", "Target=\"https://127.0.0.1:1/private.xml\" TargetMode=\"External\"") },
            changed(source, "customXml/_rels/item91.xml.rels") { it.replace("itemProps91.xml", "itemProps90.xml") },
            changed(source, "[Content_Types].xml") { it.replace("application/vnd.openxmlformats-officedocument.customXmlProperties+xml", "application/xml") },
            changed(source, "customXml/item90.xml") { it.replace("<m:title>", "<w:p xmlns:w=\"${PdfFixture.W}\"/><m:title>") }
        )
        for (input in negative) {
            val output = ByteArrayOutputStream()
            val error = assertThrows(GenerationException::class.java) {
                provider.generate(request(input), output, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
            }
            assertEquals(0, output.size())
            assertTrue(error.diagnostics.any { it.code == DiagnosticCodes.UNSUPPORTED_INPUT && it.part != null &&
                it.sourceContext?.path != null && it.sourceContext?.explanation != null })
        }
    }

    @Test
    fun `concurrent tenants never evaluate or expose stored data`() {
        val provider = provider()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..4).map { tenant -> executor.submit<Boolean> {
                val marker = "UNBOUND_TENANT_$tenant"
                val source = changed(resource("/unbound-custom-xml/a4/source.docx"), "customXml/item90.xml") {
                    it.replace("{NOT_A_TEMPLATE", "{$marker")
                }
                val output = ByteArrayOutputStream()
                provider.generate(request(source), output, GenerationContext())
                Loader.loadPDF(output.toByteArray()).use { pdf ->
                    assertFalse(PDFTextStripper().getText(pdf).contains("UNBOUND_TENANT_"))
                    assertEquals(1, pdf.numberOfPages)
                    assertImagesMatch(ImageIO.read(resource("/typography-preferences/trailing-1/page-1.png").inputStream()),
                        PDFRenderer(pdf).renderImageWithDPI(0, 96f)) {
                        pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                    }
                }
                val cleaned = ByteArrayOutputStream()
                assertTrue(provider.cleanTemplate(source.inputStream(), cleaned, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))).changes.isEmpty())
                assertArrayEquals(source, cleaned.toByteArray())
                true
            } }
            futures.forEach { assertTrue(it.get(2, TimeUnit.MINUTES)) }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS))
        }
    }

}
