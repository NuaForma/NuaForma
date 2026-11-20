package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.contracts.GenerationLogging
import dev.nuaforma.contracts.LoggingMode
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Path

class NativeTableAccessibilityTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `Word table alternative text is preserved in DOCX without printing it`() {
        val support = NativeWordFeatureSupport(temporary)
        val original = support.bytes(source)
        val parts = PdfFixture.unzip(original).toMutableMap()
        val xml = parts.getValue(part).decodeToString()
        check("</w:tblPr>" in xml)
        parts[part] = xml.replaceFirst("</w:tblPr>",
            """<w:tblCaption w:val="Quarterly totals"/><w:tblDescription w:val="{literal metadata"/></w:tblPr>""").toByteArray()
        val decorated = PdfFixture.zip(parts)

        fun render(docx: ByteArray) = Loader.loadPDF(support.generate(docx, "standard")).use { pdf ->
            PDFRenderer(pdf).renderImageWithDPI(0, 96f)
        }

        val cleaned = ByteArrayOutputStream()
        val report = support.provider.cleanTemplate(decorated.inputStream(), cleaned, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
        assertTrue(report.changes.isEmpty())
        assertArrayEquals(decorated, cleaned.toByteArray())
        assertImagesMatch(render(original), render(decorated)) {
            pixelTolerance = 0.0
            allowedDifferentPixelsPercent = 0.0
            threshold = 1
        }
    }

    @Test
    fun `malformed and duplicate captions are rejected at the original element`() {
        val support = NativeWordFeatureSupport(temporary)
        for (invalid in listOf("<w:tblCaption/>", """<w:tblCaption val="x"/>""",
            """<w:tblCaption w:val="x"><w:r/></w:tblCaption>""",
            """<w:tblCaption w:val="x"/><w:tblCaption w:val="y"/>""")) {
            support.rejectChanged(source, part, "tblCaption") { it.replaceFirst("</w:tblPr>", "$invalid</w:tblPr>") }
        }
    }

    private companion object {
        const val source = "/wide-fixed-tables/15-zero/a4/source.docx"
        const val part = "word/document.xml"
    }
}
