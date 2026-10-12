package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.Pdfa1Safety
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionCopy
import dev.nuaforma.docx.pdf.internal.ConversionFiles
import dev.nuaforma.docx.pdf.internal.PdfOptions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import java.nio.file.Files

class Pdfa1SafetyTest {
    private fun xml(value: String) = ConversionXml(GenerationContext()).read(value.toByteArray())

    @Test
    fun `drawing alpha opacity effects and style filters are rejected without changes`() {
        for (value in listOf("<fill opacity='0.25'/>", "<fill opacity2='0.8'/>", "<alpha val='99999'/>",
            "<alphaMod val='100000'/>", "<shape style='opacity:0.5'/>", "<shape style='filter:blur(1)'/>",
            "<effectLst><blur/></effectLst>", "<softEdge/>", "<glow/>", "<fill src='data:image/png;base64,not-a-resource'/>")) {
            assertEquals(DiagnosticCodes.UNSUPPORTED_INPUT, assertThrows(GenerationException::class.java) {
                Pdfa1Safety.validate(xml(value))
            }.diagnostics.single().code)
        }
        Pdfa1Safety.validate(xml("<shape><fill opacity='1'/><alpha val='100000'/><effectLst/></shape>"))
    }

    @Test
    fun `opaque PNG is accepted but alpha PNG truncated chunks and vector images are rejected`() {
        fun png(type: Int) = ByteArrayOutputStream().apply { ImageIO.write(BufferedImage(2, 2, type), "png", this) }.toByteArray()
        val opaque = png(BufferedImage.TYPE_INT_RGB)
        Pdfa1Safety.media("word/media/image.png", opaque)
        for ((name, bytes) in listOf("alpha.png" to png(BufferedImage.TYPE_INT_ARGB), "bad.png" to opaque.copyOf(32),
            "bad.png" to opaque.copyOf(opaque.size - 1), "shape.svg" to "<svg/>".toByteArray(), "fake.jpg" to opaque)) {
            assertThrows(GenerationException::class.java) { Pdfa1Safety.media(name, bytes) }
        }
    }

    @Test
    fun `assessment rejects transparent watermarks only for PDF A 1b without reading input`() {
        val provider = DocxPdfProvider()
        val options = GenerationOptions(watermark = TextWatermark("CONCEPT"))
        val status = provider.assess(GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
            FormatRequest(DocumentFormat.PDF, mapOf("profile" to "pdfa-1b")), options))
        assertEquals(AssessmentStatus.UNSUPPORTED, status.status)
        assertEquals(DiagnosticCodes.UNSUPPORTED_OPTION, status.diagnostics.single().code)
        for (profile in listOf("pdfa-2b", "pdfa-3b", "standard")) {
            assertEquals(AssessmentStatus.UNAVAILABLE, provider.assess(GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile)), options)).status)
        }
    }

    @Test
    fun `custom package locations cannot bypass the transparent image and XML guards`() {
        val alpha = ByteArrayOutputStream().apply { ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", this) }.toByteArray()
        for ((name, contentType, data) in listOf(
            Triple("custom/image.png", "image/png", alpha),
            Triple("custom/theme.data", "Application/vnd.openxmlformats-officedocument.theme+XML",
                "<theme><alpha val='50000'/></theme>".toByteArray()))) {
            val parts = PdfFixture.parts().apply {
                this[name] = data
                this["[Content_Types].xml"] = getValue("[Content_Types].xml").decodeToString()
                    .replace("</Types>", "<Override PartName='/$name' ContentType='$contentType'/></Types>").toByteArray()
            }
            val source = PdfFixture.zip(parts)
            ConversionFiles.create(GenerationContext()).use { files ->
                Files.write(files.docx, source)
                assertEquals(DiagnosticCodes.UNSUPPORTED_INPUT, assertThrows(GenerationException::class.java) {
                    ConversionCopy.prepare(files.docx, files.conversionCopy, PdfOptions.read(mapOf("profile" to "pdfa-1b")), GenerationContext())
                }.diagnostics.single().code)
                assertArrayEquals(source, Files.readAllBytes(files.docx))
                assertFalse(Files.exists(files.conversionCopy))
            }
        }
    }

    @Test
    fun `image references cannot bypass checks by using misleading content types or unsafe targets`() {
        val parts = PdfFixture.parts().apply {
            this["custom/image.png"] = ByteArrayOutputStream().apply {
                ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", this)
            }.toByteArray()
            this["[Content_Types].xml"] = getValue("[Content_Types].xml").decodeToString().replace("</Types>",
                "<Override PartName='/custom/image.png' ContentType='application/octet-stream'/></Types>").toByteArray()
            this["word/_rels/document.xml.rels"] = getValue("word/_rels/document.xml.rels").decodeToString().replace("</Relationships>",
                "<Relationship Id='image' Type='http://schemas.openxmlformats.org/officeDocument/2006/relationships/image' Target='../custom/image.png'/></Relationships>").toByteArray()
        }
        ConversionFiles.create(GenerationContext()).use { files ->
            Files.write(files.docx, PdfFixture.zip(parts))
            assertThrows(GenerationException::class.java) {
                ConversionCopy.prepare(files.docx, files.conversionCopy, PdfOptions.read(mapOf("profile" to "pdfa-1b")), GenerationContext())
            }
            assertFalse(Files.exists(files.conversionCopy))
        }
        for (target in listOf("../../secret.png", "file:///secret.png", "https://example.invalid/image.png", "media/a.png#fragment")) {
            assertThrows(GenerationException::class.java) {
                Pdfa1Safety.imageTargets(xml("<Relationships><Relationship Type='x/image' Target='$target'/></Relationships>"), "word/_rels/document.xml.rels")
            }
        }
    }
}
