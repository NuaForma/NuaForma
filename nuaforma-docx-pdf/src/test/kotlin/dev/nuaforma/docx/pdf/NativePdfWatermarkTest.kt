package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.pdf.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

class NativePdfWatermarkTest {
    @TempDir lateinit var temporary: Path
    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes() }
    private fun fonts() = listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic", "boldItalic" to "BoldItalic").map { (face, file) ->
        NativePdfFont("Liberation Sans", face, resource("/own-engine-fonts/LiberationSans-$file.ttf"))
    }
    private fun validator() = PdfProcessValidator(Path.of(System.getProperty("nuaforma.pdf.validation.java")),
        Files.list(Path.of(System.getProperty("nuaforma.pdf.validation"))).use { paths -> paths.filter { it.toString().endsWith(".jar") }.sorted().toList() }, temporary)
    private fun requirements(profile: String, text: String, opacity: Double = 0.25) = GenerationRequirements(
        FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile)),
        GenerationOptions(TextWatermark(text, "#127F87", opacity, -45.0)))

    @Test fun `dynamic watermarks pass actual independent validation for standard two-b and three-b`() {
        val provider = NativeDocxPdfProvider(NativePdfConfiguration(fonts(), validator()))
        for (profile in listOf("standard", "pdfa-2b", "pdfa-3b")) for (opacity in listOf(0.25, 1.0)) {
            val output = ByteArrayOutputStream()
            provider.generate(GenerationRequest(resource("/native-profile/word-letter.docx").inputStream(),
                requirements(profile, "{#if true}DRAFT {reference}{#else}FINAL{/if}", opacity), mapOf("reference" to "Сведения {literal}")), output, GenerationContext())
            Loader.loadPDF(output.toByteArray()).use { pdf ->
                val text = StringBuilder()
                object : PDFTextStripper() {
                    override fun processTextPosition(position: TextPosition) { text.append(position.unicode) }
                }.getText(pdf)
                assertTrue(text.contains("DRAFT Сведения {literal}"), profile)
                assertTrue(text.contains("Bevestiging van uw aanvraag"), profile)
                assertEquals(1, pdf.numberOfPages)
                assertEquals(if (opacity == 1.0) emptyList<Float>() else listOf(opacity.toFloat()), pdf.getPage(0).resources.extGStateNames.map { pdf.getPage(0).resources.getExtGState(it).nonStrokingAlphaConstant })
                assertTrue(pdf.getPage(0).resources.fontNames.all { pdf.getPage(0).resources.getFont(it).isEmbedded })
            }
        }
        Files.list(temporary).use { assertEquals(0L, it.count()) }
    }

    @Test fun `one-b rejects every requested watermark before reading source including opaque or empty-resolving text`() {
        val provider = NativeDocxPdfProvider(NativePdfConfiguration(fonts(), PdfConformanceValidator { _, _ -> }))
        for (text in listOf("DRAFT", "{#if false}DRAFT{/if}")) for (opacity in listOf(0.0, 0.25, 1.0)) {
            val requirements = requirements("pdfa-1b", text, opacity)
            assertEquals(AssessmentStatus.UNSUPPORTED, provider.assess(requirements).status)
            val read = AtomicBoolean()
            val source = object : InputStream() { override fun read(): Int { read.set(true); error("Must reject before source access") } }
            val output = ByteArrayOutputStream()
            val error = assertThrows(GenerationException::class.java) { provider.generate(GenerationRequest(source, requirements), output, GenerationContext()) }
            assertEquals(DiagnosticCodes.UNSUPPORTED_OPTION, error.diagnostics.first().code)
            assertFalse(read.get())
            assertEquals(0, output.size())
        }
    }

    @Test fun `overlong resolved decoration fails with no partial PDF or data in diagnostics`() {
        val provider = NativeDocxPdfProvider(NativePdfConfiguration(fonts(), validator()))
        val output = ByteArrayOutputStream()
        val canary = "PRIVATE-CANARY-".repeat(350)
        val error = assertThrows(GenerationException::class.java) {
            provider.generate(GenerationRequest(resource("/native-profile/word-letter.docx").inputStream(), requirements("standard", "{reference}"),
                mapOf("reference" to canary)), output, GenerationContext())
        }
        assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, error.diagnostics.first().code)
        assertEquals(0, output.size())
        assertFalse(error.message.orEmpty().contains("PRIVATE-CANARY"))
        Files.list(temporary).use { assertEquals(0L, it.count()) }
    }
}
