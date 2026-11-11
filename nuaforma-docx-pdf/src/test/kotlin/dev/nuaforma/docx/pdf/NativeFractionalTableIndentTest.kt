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
import java.nio.file.Path

class NativeFractionalTableIndentTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `fractional indents and explicit zero retain Word placement and style inheritance`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        for (mode in listOf("12", "15")) for (kind in listOf("fraction", "zero", "style-zero")) {
            val folder = "/fractional-table-indent/$mode-$kind/$paper/"
            val profiles = if (mode == "15" && kind == "fraction" && paper == "a4")
                listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard")
            support.verify(folder, profiles, Regex("I[0-9]{2}[AB]"), 6, golden = if (kind == "fraction")
                folder + "page-1.png" else "/implicit-table-indent/$mode-explicit/$paper/page-1.png")
        }
        for (mode in listOf("12", "15")) {
            val raw = support.bytes("/fractional-table-indent/$mode-fraction/$paper/source.docx")
            val floor = support.bytes("/fractional-table-indent/$mode-floor/$paper/source.docx")
            Loader.loadPDF(support.generate(raw, "standard")).use { actual ->
                Loader.loadPDF(support.generate(floor, "standard")).use { control ->
                    assertImagesMatch(PDFRenderer(control).renderImageWithDPI(0, 96f), PDFRenderer(actual).renderImageWithDPI(0, 96f)) {
                        pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                    }
                }
            }
        }
    }

    @Test
    fun `direct reading keeps the zero declaration and does not modify source bytes`() {
        val support = NativeWordFeatureSupport(temporary)
        for (mode in listOf("12", "15")) {
            val context = GenerationContext()
            val reference = NativeDocxReader(context, { _, _, _ -> "fixture" }).read(support.bytes("/implicit-table-indent/$mode-explicit/a4/source.docx"))
            for (kind in listOf("zero", "style-zero")) {
                val source = support.bytes("/fractional-table-indent/$mode-$kind/a4/source.docx")
                val before = source.copyOf()
                val actual = NativeDocxReader(context, { _, _, _ -> "fixture" }).read(source)
                assertEquals(reference.blocks.filterIsInstance<PdfTable>().single().leftIndent,
                    actual.blocks.filterIsInstance<PdfTable>().single().leftIndent)
                assertArrayEquals(before, source)
            }
        }
    }

    @Test
    fun `invalid physical indentation values units duplicates and attributes fail before binding`() {
        val support = NativeWordFeatureSupport(temporary)
        val source = "/fractional-table-indent/15-fraction/a4/source.docx"
        for (value in listOf("-288000.1", "288000.1", "1e3", "10%")) support.rejectChanged(source, "word/document.xml", "tblInd") {
            it.replace("<w:tblInd w:w=\"100.8\" w:type=\"dxa\"/>", "<w:tblInd w:w=\"$value\" w:type=\"dxa\"/>")
        }
        for (replacement in listOf("<w:tblInd w:w=\"100.8\" w:type=\"pct\"/>",
            "<w:tblInd w:w=\"100.8\" w:type=\"dxa\" foreign=\"x\"/>",
            "<w:tblInd w:w=\"100.8\" w:type=\"dxa\"/><w:tblInd w:w=\"0\" w:type=\"dxa\"/>")) {
            support.rejectChanged(source, "word/document.xml", "tblInd") {
                it.replace("<w:tblInd w:w=\"100.8\" w:type=\"dxa\"/>", replacement)
            }
        }
    }

}
