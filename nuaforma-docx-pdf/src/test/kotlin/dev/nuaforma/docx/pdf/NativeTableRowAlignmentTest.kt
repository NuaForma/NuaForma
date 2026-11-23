package dev.nuaforma.docx.pdf

import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class NativeTableRowAlignmentTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `row alignment identical to the effective table alignment preserves pixels`() {
        val support = NativeWordFeatureSupport(temporary)
        for (paper in listOf("a4", "letter")) {
            val parts = PdfFixture.unzip(support.bytes(source(paper)))
            val original = parts.getValue(part).decodeToString()
            val base = tableAlignment(original, "center")
            val repeated = rowAlignment(base, """<w:jc w:val="center"/>""")
            val a = support.generate(PdfFixture.zip(parts + (part to base.toByteArray())), "standard")
            val b = support.generate(PdfFixture.zip(parts + (part to repeated.toByteArray())), "standard")
            Loader.loadPDF(a).use { control ->
                Loader.loadPDF(b).use { aligned ->
                    assertEquals(control.numberOfPages, aligned.numberOfPages)
                    val x = PDFRenderer(control)
                    val y = PDFRenderer(aligned)
                    repeat(control.numberOfPages) { page ->
                        assertImagesMatch(x.renderImageWithDPI(page, 96f), y.renderImageWithDPI(page, 96f)) {
                            pixelTolerance = 0.0
                            allowedDifferentPixelsPercent = 0.0
                            threshold = 1
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `different or malformed row alignments still identify their source`() {
        val support = NativeWordFeatureSupport(temporary)
        for (row in listOf(
            """<w:jc w:val="right"/>""",
            """<w:jc w:val="illegal"/>""",
            """<w:jc w:val="center"/><w:jc w:val="center"/>""",
        )) {
            support.rejectChanged(source("a4"), part, "jc") { rowAlignment(tableAlignment(it, "center"), row) }
        }
    }

    private fun tableAlignment(xml: String, alignment: String): String =
        xml.replace(Regex("<w:tblPr>.*?</w:tblPr>", RegexOption.DOT_MATCHES_ALL)) { table ->
            table.value.replace(Regex("<w:jc\\b[^>]*/>"), "").replace("</w:tblPr>", """<w:jc w:val="$alignment"/></w:tblPr>""")
        }

    private fun rowAlignment(xml: String, element: String): String =
        xml.replace(Regex("<w:tr\\b[^>]*>.*?</w:tr>", RegexOption.DOT_MATCHES_ALL)) { row ->
            val value = row.value
            if ("<w:trPr>" in value) value.replace("<w:trPr>", "<w:trPr>$element")
            else value.substringBefore('>') + "><w:trPr>$element</w:trPr>" + value.substringAfter('>')
        }

    private fun source(paper: String) = "/implicit-table-indent/absent-explicit/$paper/source.docx"

    private companion object {
        const val part = "word/document.xml"
    }
}
