package dev.nuaforma.docx.pdf

import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class NativeNilTableIndentationTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `nil indentation has the same print geometry as an omitted table indent`() {
        val support = NativeWordFeatureSupport(temporary)
        val template = PdfFixture.unzip(support.bytes(source))
        val xml = template.getValue(part).decodeToString()

        fun render(indent: String) = Loader.loadPDF(support.generate(
            PdfFixture.zip(template + (part to replaceIndent(xml, indent).toByteArray())), "standard"
        )).use { PDFRenderer(it).renderImageWithDPI(0, 96f) }

        assertImagesMatch(render(""), render(nil)) {
            pixelTolerance = 0.0
            allowedDifferentPixelsPercent = 0.0
            threshold = 1
        }
    }

    @Test
    fun `nil indentation with a nonzero measurement reports its source property`() {
        val support = NativeWordFeatureSupport(temporary)
        support.rejectChanged(source, part, "tblInd") {
            replaceIndent(it, nil.replace("w:w=\"0\"", "w:w=\"5\""))
        }
    }

    private fun replaceIndent(xml: String, replacement: String): String =
        xml.replaceFirst(Regex("""<w:tblInd\b[^>]*/>"""), replacement)

    private companion object {
        const val source = "/table-style-defaults/a4/source.docx"
        const val part = "word/document.xml"
        const val nil = """<w:tblInd w:type="nil" w:w="0"/>"""
    }
}
