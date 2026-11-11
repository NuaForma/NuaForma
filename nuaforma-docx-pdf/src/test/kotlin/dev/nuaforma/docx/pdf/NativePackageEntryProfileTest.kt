package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import javax.imageio.ImageIO

class NativePackageEntryProfileTest {
    @TempDir lateinit var temporary: Path

    private fun expanded(source: ByteArray, count: Int) = PdfFixture.zip(PdfFixture.unzip(source).toMutableMap().apply {
        require(size <= count)
        repeat(count - size) { put("[trash]/%04x.dat".format(it), byteArrayOf(0, -1, 42)) }
    })

    private fun content(document: org.apache.pdfbox.pdmodel.PDDocument) =
        PDFTextStripper().apply { sortByPosition = true }.getText(document).replace(Regex("[\\p{Z}\\s]+"), "")

    @Test
    fun `larger inert packages retain existing Word contents and immutable pixels`() {
        val support = NativeWordFeatureSupport(temporary)
        val context = GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))
        for (paper in listOf("a4", "letter")) {
            val folder = "/sequence-code-formatting/controls/complex-default/$paper/"
            Loader.loadPDF(support.bytes(folder + "word-reference.pdf")).use { word ->
                for (count in listOf(65, 256)) {
                    val source = expanded(support.bytes(folder + "source.docx"), count)
                    val original = source.copyOf()
                    val cleaned = ByteArrayOutputStream()
                    assertTrue(support.provider.cleanTemplate(source.inputStream(), cleaned, context).changes.isEmpty())
                    assertArrayEquals(source, cleaned.toByteArray())
                    val twice = ByteArrayOutputStream()
                    assertTrue(support.provider.cleanTemplate(cleaned.toByteArray().inputStream(), twice, context).changes.isEmpty())
                    assertArrayEquals(source, twice.toByteArray())
                    val profiles = if (paper == "a4" && count == 256) listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b")
                        else listOf("standard")
                    for (profile in profiles) Loader.loadPDF(support.generate(source, profile)).use { actual ->
                        assertEquals(1, actual.numberOfPages)
                        assertEquals(content(word), content(actual))
                        assertEquals(word.getPage(0).mediaBox.width, actual.getPage(0).mediaBox.width, .1f)
                        assertEquals(word.getPage(0).mediaBox.height, actual.getPage(0).mediaBox.height, .1f)
                        assertTrue(actual.getPage(0).resources.fontNames.all { actual.getPage(0).resources.getFont(it).isEmbedded })
                        assertImagesMatch(ImageIO.read(support.bytes(folder + "page-1.png").inputStream()),
                            PDFRenderer(actual).renderImageWithDPI(0, 96f)) {
                            pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                        }
                    }
                    assertArrayEquals(original, source)
                }
            }
        }
    }

    @Test
    fun `too many members and lower caller ceilings cannot publish PDF or cleanup output`() {
        val support = NativeWordFeatureSupport(temporary)
        val original = support.bytes("/sequence-code-formatting/controls/complex-default/a4/source.docx")
        for ((count, limit) in listOf(257 to 10_000, 256 to 255, 65 to 64)) {
            val source = expanded(original, count)
            val context = GenerationContext(GenerationLimits(maxArchiveEntries = limit), CancellationToken.NONE, null,
                java.time.Clock.systemUTC(), GenerationLogging(LoggingMode.DEVELOPMENT))
            val output = ByteArrayOutputStream()
            val failure = assertThrows(GenerationException::class.java) {
                support.provider.generate(GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                    FormatRequest(DocumentFormat.PDF, mapOf("profile" to "standard")))), output, context)
            }
            assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, failure.diagnostics.single().code)
            assertEquals(0, output.size())
            val cleaned = ByteArrayOutputStream()
            val cleanup = assertThrows(GenerationException::class.java) {
                support.provider.cleanTemplate(source.inputStream(), cleaned, context)
            }
            assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, cleanup.diagnostics.single().code)
            assertEquals(0, cleaned.size())
        }
    }
}
