package dev.nuaforma.docx.pdf

import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class NativeConditionalTableMetadataTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `Word derives the painted regions from the table instead of cached condition flags`() {
        val support = NativeWordFeatureSupport(temporary)
        val original = support.bytes(source)
        val parts = PdfFixture.unzip(original)
        val document = parts.getValue(part).decodeToString()
        val record = """<w:cnfStyle w:val="000000000000" w:firstColumn="1"/>"""
        val modified = PdfFixture.zip(parts + (part to withMetadata(document, record).toByteArray()))

        fun image(bytes: ByteArray) = Loader.loadPDF(support.generate(bytes, "standard")).use {
            PDFRenderer(it).renderImageWithDPI(0, 96f)
        }

        assertImagesMatch(image(original), image(modified)) {
            pixelTolerance = 0.0
            allowedDifferentPixelsPercent = 0.0
            threshold = 1
        }
    }

    @Test
    fun `invalid condition flags retain a source diagnostic`() {
        NativeWordFeatureSupport(temporary).rejectChanged(source, part, "cnfStyle") {
            withMetadata(it, """<w:cnfStyle w:val="101"/>""")
        }
    }

    private fun withMetadata(xml: String, record: String): String {
        val before = xml.substringBefore("<w:tbl>")
        val after = xml.substringAfter("</w:tbl>")
        var table = xml.substringAfter("<w:tbl>").substringBefore("</w:tbl>")
        for (owner in listOf("trPr", "tcPr", "pPr")) table = table.replace("<w:$owner>", "<w:$owner>$record")
        return "$before<w:tbl>$table</w:tbl>$after"
    }

    private companion object {
        const val source = "/conditional-tables/a4/source.docx"
        const val part = "word/document.xml"
    }
}
