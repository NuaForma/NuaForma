package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.adapters.nativeengine.NativeDocxReader
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import dev.nuaforma.pdf.PdfTable
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.nio.file.Path

class NativeSignedTableIndentTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `signed direct and inherited table placement retains measured Word geometry`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        for (mode in listOf("12", "15")) for (kind in listOf("fraction", "truncate", "floor", "style-fraction", "negative-zero", "zero", "direct-zero")) {
            val folder = "/signed-table-indent/$mode-$kind/$paper/"
            val golden = when (kind) {
                "negative-zero", "zero", "direct-zero" -> "/implicit-table-indent/$mode-explicit/$paper/page-1.png"
                "floor" -> "/signed-table-indent/$mode-floor/$paper/page-1.png"
                else -> "/signed-table-indent/$mode-truncate/$paper/page-1.png"
            }
            val profiles = if (kind == "fraction" && paper == "a4")
                listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard")
            support.verify(folder, profiles, Regex("I[0-9]{2}[AB]"), 6, golden = golden)
        }
    }

    @Test
    fun `signed fractions truncate toward zero and keep explicit zero instead of omission`() {
        val support = NativeWordFeatureSupport(temporary)

        fun indent(bytes: ByteArray) = NativeDocxReader(GenerationContext(), { _, _, _ -> "fixture" })
            .read(bytes).blocks.filterIsInstance<PdfTable>().single().leftIndent

        for (mode in listOf("12", "15")) {
            val zero = indent(support.bytes("/implicit-table-indent/$mode-explicit/a4/source.docx"))
            for (kind in listOf("fraction", "truncate", "style-fraction", "negative-zero", "direct-zero")) {
                val source = support.bytes("/signed-table-indent/$mode-$kind/a4/source.docx")
                val before = source.copyOf()
                assertEquals(zero - if (kind in listOf("negative-zero", "direct-zero")) 0f else 5f, indent(source), .001f)
                assertArrayEquals(before, source)
            }
        }
    }

    @Test
    fun `out of range and malformed signed indents reject without publishing PDF`() {
        val support = NativeWordFeatureSupport(temporary)
        for (value in listOf("-288000.1", "288000.1", "--1", "1e3", "-10%", "NaN", "-Infinity")) {
            support.rejectChanged("/signed-table-indent/15-fraction/a4/source.docx", "word/document.xml", "tblInd") {
                it.replace("w:w=\"-100.8\"", "w:w=\"$value\"")
            }
        }
    }

    @Test
    fun `cleanup canonicalizes signed fractions and keeps the same reusable PDF`() {
        val support = NativeWordFeatureSupport(temporary)
        for (mode in listOf("12", "15")) for (kind in listOf("fraction", "style-fraction", "negative-zero")) {
            val source = support.bytes("/signed-table-indent/$mode-$kind/a4/source.docx")
            val cleaned = ByteArrayOutputStream()

            fun context() = GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))

            val report = support.provider.cleanTemplate(source.inputStream(), cleaned, context())
            assertTrue(report.changes.isNotEmpty())
            assertTrue(report.changes.all { it.action == TemplateCleanupAction.RESTORE_REQUIRED_STRUCTURE })
            val second = ByteArrayOutputStream()
            assertTrue(support.provider.cleanTemplate(cleaned.toByteArray().inputStream(), second, context()).changes.isEmpty())
            assertArrayEquals(cleaned.toByteArray(), second.toByteArray())
            Loader.loadPDF(support.generate(source, "standard")).use { before ->
                Loader.loadPDF(support.generate(cleaned.toByteArray(), "standard")).use { after ->
                    assertImagesMatch(PDFRenderer(before).renderImageWithDPI(0, 96f), PDFRenderer(after).renderImageWithDPI(0, 96f)) {
                        pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                    }
                }
            }
        }
    }
}
